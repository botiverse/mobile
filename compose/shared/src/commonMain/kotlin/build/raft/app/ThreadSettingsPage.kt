package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.base.Utils
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedHomeState
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.designsystem.components.RaftSideDrawerDefaults
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.LocalRaftSurfaceColor
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A conversation's settings (channel or DM) on a phone: a page pushed over the conversation like
 * every other page (Members, a file, a reply thread), with Back at the start of its head and no ×
 * (web ChannelOverflowMenu below md: the sheet fills the screen behind a Back arrow). The platform
 * page stack moves it (slide in from the end, the system back gesture, the iOS edge swipe). On a
 * wide window the settings stay the side drawer beside the conversation.
 */
internal const val THREAD_SETTINGS_PAGE = "compose_thread_settings"

/** pageData key: the [ThreadSettingsPageLinks] entry of the conversation that opened the page. */
internal const val THREAD_SETTINGS_LINK_KEY = "threadSettingsLink"

/**
 * True where the settings open as a pushed page: a window no wider than the drawer, which would
 * fill it anyway (a phone). Wider windows keep the drawer as a panel beside the conversation.
 */
internal fun threadSettingsOpensAsPage(windowWidth: Dp): Boolean = windowWidth <= RaftSideDrawerDefaults.MaxWidth

/** [threadSettingsOpensAsPage] for the current window. */
@Composable
internal fun rememberThreadSettingsOpensAsPage(): Boolean {
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    return threadSettingsOpensAsPage(width)
}

internal fun threadSettingsPageData(linkKey: String, serverId: ServerId, channelId: ChannelId): JSONObject =
    JSONObject().apply {
        put("pageName", THREAD_SETTINGS_PAGE)
        put("instanceId", "thread-settings-${channelId.value}")
        put(THREAD_SETTINGS_LINK_KEY, linkKey)
        put("serverId", serverId.value)
        put("channelId", channelId.value)
    }

/**
 * Where the settings page reads the conversation's state and sends what its rows do: the same
 * stores and callbacks the conversation's drawer uses, so both show and do the same. Live
 * sources (flows), not values, so the page stays current while the conversation under it is
 * covered and not recomposing.
 */
internal class ThreadSettingsPageSources(
    val directMessage: Boolean,
    /** The head: the channel's name (no "#") or the DM peer's name, its kind and description. */
    val title: String,
    val channelKind: String?,
    val subtitle: String?,
    /** The conversation's render state: its payload (mute and pin fallbacks) and write access. */
    val renderSlices: Flow<SharedThreadRenderSlice>,
    val initialRenderSlice: SharedThreadRenderSlice,
    val homeState: StateFlow<SharedHomeState>,
    val hasHomeStore: Boolean,
    val onHomeIntent: (SharedHomeIntent) -> Unit,
    val projections: Flow<Map<String, SharedConversationDirectoryProjection>>,
    val initialProjections: Map<String, SharedConversationDirectoryProjection>,
    /** The conversation's own projection among [projections] (the header's display rule). */
    val selectProjection: (Map<String, SharedConversationDirectoryProjection>) -> SharedConversationDirectoryProjection?,
    val serverId: ServerId?,
    val channelId: ChannelId?,
    val members: Flow<SharedServerChannelMembersSlice>,
    val initialMembers: SharedServerChannelMembersSlice,
    val agentStatusResolver: RaftAgentStatusResolver,
    val collapseServerId: String?,
    val collapseConversationId: String?,
    /** The drawer's store, actions and follow-ups for [ThreadChannelSettingsPanel]. */
    val completePanel: (ThreadChannelSettingsPanel, SharedHomeContentState, onLeave: () -> Unit) -> ThreadChannelSettingsPanel,
    val onOpenMembers: (add: Boolean) -> Unit,
    /** Leave the conversation once it is back on screen (after Leave, Delete, Disconnect, Archive). */
    val onLeaveConversation: () -> Unit,
)

/** One conversation's link to its settings page; the conversation keeps [sources] current. */
@Stable
internal class ThreadSettingsPageLink {
    var sources: ThreadSettingsPageSources? by mutableStateOf(null)
}

/**
 * The open conversations' settings links, by key (process memory, main thread). A settings page
 * whose conversation is gone (a restored process) finds none and closes.
 */
internal object ThreadSettingsPageLinks {
    private val links = mutableMapOf<String, ThreadSettingsPageLink>()

    fun register(key: String, link: ThreadSettingsPageLink) {
        links[key] = link
    }

    fun unregister(key: String, link: ThreadSettingsPageLink) {
        if (links[key] === link) links.remove(key)
    }

    fun get(key: String): ThreadSettingsPageLink? = links[key]
}

/**
 * The settings' frame. [asPage] false: the side drawer ([RaftUnifiedSideDrawer], × at the end of
 * its head). [asPage] true: a page, the drawer's head with the page Back at its start in place of
 * the × ([closeLabel] is unused), then the body over the bottom safe area.
 */
@Composable
internal fun ThreadSettingsSurface(
    asPage: Boolean,
    title: String,
    titleTrailing: (@Composable RowScope.() -> Unit)?,
    closeLabel: String,
    onDismiss: () -> Unit,
    dismissOnOutsideTap: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!asPage) {
        RaftUnifiedSideDrawer(
            title = title,
            titleTrailing = titleTrailing,
            closeLabel = closeLabel,
            dismissOnOutsideTap = dismissOnOutsideTap,
            onDismiss = onDismiss,
            content = content,
        )
        return
    }
    val colors = RaftTheme.colors
    CompositionLocalProvider(LocalRaftContentColor provides colors.foreground, LocalRaftSurfaceColor provides colors.layerPopover) {
        Column(Modifier.fillMaxSize().background(colors.layerPopover)) {
            RaftTopPageHeader(
                title = title,
                onBack = onDismiss,
                backgroundColor = colors.layerPopover,
                titleContent = {
                    // The drawer head's one line: the title, then its secondary item on the baseline.
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RaftText(
                            title,
                            Modifier.alignByBaseline().semantics { heading() },
                            style = RaftTheme.typography.headline,
                            color = colors.foreground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        titleTrailing?.invoke(this)
                    }
                },
            )
            Column(
                Modifier.fillMaxWidth().weight(1f).padding(bottom = raftBottomSafeAreaPadding()),
                content = content,
            )
        }
    }
}

@Page(THREAD_SETTINGS_PAGE, supportInLocal = true)
internal class ComposeThreadSettingsPage : AuthenticatedProductPager(AuthenticatedProductRoute.ThreadSettings) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent {
            RaftInputFocusDismissHost { focusDismissModifier ->
                Box(modifier = focusDismissModifier.fillMaxSize()) {
                    ThreadSettingsPageScreen()
                }
            }
        }
    }
}

@Composable
private fun ThreadSettingsPageScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val router = remember(pager) { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME) }
    val linkKey = remember(pager.pageData.params.toString()) { pager.pageData.params.optString(THREAD_SETTINGS_LINK_KEY) }
    val sources = ThreadSettingsPageLinks.get(linkKey)?.sources
    if (sources == null) {
        // The conversation that opened it is gone (a restored process): nothing to show.
        LaunchedEffect(linkKey) {
            bridge.logWarn("thread_settings_page_close reason=no_conversation")
            router.closePage()
        }
        Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerPopover))
        return
    }
    RaftDesignSystemTheme {
        ThreadSettingsPageBody(sources = sources, onClose = router::closePage)
    }
}

/**
 * The page's body from [sources], as the conversation builds its drawer: the members strip, Info,
 * Guest access, Preferences and Actions of a channel ([ThreadSettingsModal]), or a DM's
 * Preferences ([ThreadDirectMessageSettingsDrawer]), each as a page.
 */
@Composable
internal fun ThreadSettingsPageBody(sources: ThreadSettingsPageSources, onClose: () -> Unit) {
    val home by sources.homeState.collectAsState()
    val projections by sources.projections.collectAsState(sources.initialProjections)
    val members by sources.members.collectAsState(sources.initialMembers)
    val renderSlice by sources.renderSlices.collectAsState(sources.initialRenderSlice)
    val payload = renderSlice.payload
    val writeActionsEnabled = renderSlice.writeActionsEnabled
    val projection = sources.selectProjection(projections)
    val activityMuteState =
        remember(payload, projection, sources.hasHomeStore) {
            payload.threadActivityMuteState(projection)?.takeIf { sources.hasHomeStore }
        }
    val sidebarOrder = (home.content as? SharedHomeContentState.Content)?.snapshot?.sidebarOrder
    val pinState =
        remember(payload, projection, sidebarOrder) {
            payload.threadConversationPinTarget(projection)?.toUiState(sidebarOrder)
        }
    val collapse = rememberRaftLongMessageCollapseConversationSetting(sources.collapseServerId, sources.collapseConversationId)
    // As the conversation hands them to its drawer: mute only while it can write.
    val drawerMuteState = activityMuteState?.takeIf { writeActionsEnabled }
    val onToggleActivityMute: (() -> Unit)? =
        drawerMuteState?.let { muteState ->
            { sources.onHomeIntent(SharedHomeIntent.SetChannelActivityMuted(conversationId = muteState.channelId, muted = !muteState.muted)) }
        }
    val panel =
        rememberThreadChannelSettingsPanel(
            homeContent = home.content,
            serverId = sources.serverId,
            channelId = sources.channelId,
            memberSlice = members,
            pinState = pinState,
            activityMuteState = activityMuteState,
            longMessageCollapse = collapse,
            agentStatusResolver = sources.agentStatusResolver,
            writeActionsEnabled = writeActionsEnabled,
            onHomeIntent = sources.onHomeIntent,
            directMessage = sources.directMessage,
        )?.let { sources.completePanel(it, home.content, sources.onLeaveConversation) }
    if (sources.directMessage) {
        ThreadDirectMessageSettingsDrawer(
            name = sources.title,
            activityMuteState = drawerMuteState,
            section = panel?.section,
            sectionEnabled = panel?.writeActionsEnabled == true,
            longMessageCollapseSetting = collapse,
            onDismiss = onClose,
            onToggleActivityMute = onToggleActivityMute,
            onMoveSection = { moves -> panel?.onMoveSection?.invoke(moves) },
            onCreateSection = { name, emoji -> panel?.onCreateSection?.invoke(name, emoji) },
            asPage = true,
        )
    } else {
        // The channel's name and description as Home has them now (a Save here updates Home).
        val homeRow = panel?.channel
        ThreadSettingsModal(
            channelKind = sources.channelKind,
            channelName = homeRow?.name?.removePrefix("#")?.takeIf { it.isNotBlank() } ?: sources.title,
            subtitle = if (homeRow != null) homeRow.subtitle else sources.subtitle,
            panel =
                (panel ?: ThreadChannelSettingsPanel(longMessageCollapse = collapse)).copy(
                    onOpenMembers = { sources.onOpenMembers(false) },
                    onAddMember = { sources.onOpenMembers(true) },
                ),
            activityMuteState = drawerMuteState,
            onDismiss = onClose,
            onToggleActivityMute = onToggleActivityMute,
            asPage = true,
        )
    }
}
