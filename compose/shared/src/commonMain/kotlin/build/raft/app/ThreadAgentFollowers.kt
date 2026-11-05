package build.raft.app

import build.raft.app.resources.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import build.raft.app.data.ThreadFollowersNotice
import build.raft.app.data.ThreadFollowersNoticeKind
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import build.raft.app.model.MemberKind
import build.raft.app.base.Utils
import build.raft.app.data.RealThreadAgentFollowersRepository
import build.raft.app.data.RealtimeGuardedActiveThreadSink
import build.raft.app.data.SharedThreadAgentFollowersStore
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.ThreadAgentFollower
import build.raft.app.data.ThreadAgentFollowersIntent
import build.raft.app.data.ThreadAgentFollowersState
import build.raft.app.data.ThreadFollowersError
import build.raft.app.model.ThreadFollowersUpdated
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.compat.pager.Pager
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftListRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextAlign
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftListRowText
import build.raft.designsystem.components.RaftPopoverSurface
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.components.RaftJointServerBadge

@Composable
internal fun ThreadAgentFollowersRoute(
    threadId: String?, serverId: String?, accountId: String?,
    storeScope: RaftShellStoreScope, active: Boolean,
    onOpenAgentProfile: (MemberDetailIdentity) -> Unit = {},
    /** Task #20: the thread's live member-status source (the one its message avatars read). */
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
) {
    if (threadId.isNullOrBlank() || serverId.isNullOrBlank() || accountId.isNullOrBlank()) return
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager) { Utils.bridgeModule(pager.pagerId) }
    val shared = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val environment by shared.apiEnvironmentStore.state.collectAsState()
    val environmentId = environment.apiBaseUrl.trim().trimEnd('/')
    val scope = rememberCoroutineScope()
    val store = remember(shared, storeScope, accountId, serverId, threadId, environmentId) {
        SharedThreadAgentFollowersStore(
            RealThreadAgentFollowersRepository(
                network = shared.networkClient(storeScope), auth = shared.authStore(storeScope),
                accountId = accountId, serverId = serverId, threadId = threadId,
                environmentId = environmentId,
                currentEnvironment = { shared.apiEnvironmentStore.current().apiBaseUrl.trim().trimEnd('/') },
            ), scope,
        )
    }
    val state by store.state.collectAsState()
    val latestActive by rememberUpdatedState(active)
    val owner = remember(shared, storeScope) { shared.realtimeDomainEventOwner(storeScope) }
    DisposableEffect(store, owner) {
        val sink = RealtimeGuardedActiveThreadSink { eventServerId, event ->
            if (latestActive && eventServerId == serverId && event is ThreadFollowersUpdated && event.threadChannelId.value == threadId) {
                store.dispatch(ThreadAgentFollowersIntent.Refresh)
            }
        }
        owner.registerGuardedActiveThreadSink(sink)
        onDispose {
            owner.unregisterGuardedActiveThreadSink(sink)
            store.close()
        }
    }
    LaunchedEffect(store, active) {
        if (active) store.dispatch(ThreadAgentFollowersIntent.Refresh)
        else store.dispatch(ThreadAgentFollowersIntent.Close)
    }
    ThreadAgentFollowersHeader(state, store::dispatch, onOpenAgentProfile, agentStatusResolver)
}

private data class FollowersOverlay(
    val state: ThreadAgentFollowersState,
    val anchor: RaftPopoverAnchorResolver?,
    val dispatch: (ThreadAgentFollowersIntent) -> Unit,
    val openAgentProfile: (ThreadAgentFollower) -> Unit,
    val agentStatusResolver: RaftAgentStatusResolver,
)
private class FollowersOverlayOwner {
    var value by mutableStateOf<FollowersOverlay?>(null)
}
private val LocalFollowersOverlay = staticCompositionLocalOf<FollowersOverlayOwner?> { null }

/** Root sibling overlays: the notice never traps touches or disappears with the last avatar. */
@Composable
internal fun ThreadFollowersOverlayHost(content: @Composable () -> Unit) {
    val owner = remember { FollowersOverlayOwner() }
    CompositionLocalProvider(LocalFollowersOverlay provides owner) {
        Box(Modifier.fillMaxSize()) {
            content()
            owner.value?.let { overlay ->
                val anchor = if (overlay.state.open) overlay.anchor?.resolveCurrentPlacement() else null
                if (anchor != null) {
                    FollowersPopover(overlay.state, anchor, overlay.dispatch, overlay.openAgentProfile, overlay.agentStatusResolver)
                }
                overlay.state.notice?.let { notice ->
                    FollowerNoticeView(
                        notice = notice,
                        canUndo = notice.kind == ThreadFollowersNoticeKind.Removed && overlay.state.undo != null,
                        enabled = !overlay.state.mutating,
                        onUndo = { overlay.dispatch(ThreadAgentFollowersIntent.Undo) },
                        modifier = Modifier.align(Alignment.BottomCenter).zIndex(RaftFloatingOverlayHostZIndex + 1f),
                    )
                }
            }
        }
    }
}

/** raft-ui 0.5.15 toast: tone surface, title row, and a separate right-aligned action row. */
@Composable
private fun FollowerNoticeView(
    notice: ThreadFollowersNotice, canUndo: Boolean, enabled: Boolean,
    onUndo: () -> Unit, modifier: Modifier = Modifier,
) {
    val isError = notice.kind == ThreadFollowersNoticeKind.RemoveFailed || notice.kind == ThreadFollowersNoticeKind.RestoreFailed
    val label = stringResource(when (notice.kind) {
        ThreadFollowersNoticeKind.Removed -> Res.string.thread_followers_removed
        ThreadFollowersNoticeKind.Restored -> Res.string.thread_followers_restored
        ThreadFollowersNoticeKind.RemoveFailed -> Res.string.thread_followers_remove_failed
        ThreadFollowersNoticeKind.RestoreFailed -> Res.string.thread_followers_restore_failed
    }, notice.agentLabel)
    val shape = RoundedCornerShape(12.dp)
    val surface = Color(0xED2C2C2E) // raft-color: fixed dark toast surface in both modes
    val ink = RaftColor.StickerPaper
    Box(modifier.padding(16.dp).widthIn(max = 384.dp)) {
        Column(
            Modifier.fillMaxWidth().background(surface, shape)
                .border(1.dp, Color.White.copy(alpha = 0.2f), shape) // raft-color: edge on fixed dark toast
                .raftNativeDispatchCapture().clickable { }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RaftLucideIcon(if (isError) RaftLucideIconName.Info else RaftLucideIconName.CircleCheck,
                    Modifier.size(16.dp), color = raftArgbHex(RaftColor.StickerPaper))
                // raft-ui toast title: text-callout font-normal (14/20 400).
                Text(label, style = RaftType.Callout, color = ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            if (canUndo) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    val buttonShape = RoundedCornerShape(100.dp)
                    Box(Modifier.heightIn(min = 28.dp)
                        .background(Color.White.copy(alpha = 0.06f), buttonShape) // raft-color: wash on fixed dark toast
                        .border(1.dp, Color.White.copy(alpha = 0.14f), buttonShape) // raft-color: edge on fixed dark toast
                        .clickable(enabled = enabled, onClick = onUndo).padding(horizontal = 8.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(Res.string.common_undo), style = RaftType.Caption, color = RaftColor.StickerPaper)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ThreadAgentFollowersHeader(
    state: ThreadAgentFollowersState,
    dispatch: (ThreadAgentFollowersIntent) -> Unit,
    onOpenAgentProfile: (MemberDetailIdentity) -> Unit = {},
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
) {
    val agents = state.roster?.agents.orEmpty()
    val owner = LocalFollowersOverlay.current
    val anchorRef = remember { RaftLiveRootAnchorRef("thread-followers") }
    var resolver by remember { mutableStateOf<RaftPopoverAnchorResolver?>(null) }
    val latestDispatch by rememberUpdatedState(dispatch)
    val stableDispatch = remember { { intent: ThreadAgentFollowersIntent -> latestDispatch(intent) } }
    val latestOpenAgentProfile by rememberUpdatedState(onOpenAgentProfile)
    // Web ThreadAgentFollowerRow: the row opens the agent's profile (a peer agent's too). The
    // popover closes first so the profile is not opened under it.
    val openAgentProfile = remember {
        { agent: ThreadAgentFollower ->
            latestDispatch(ThreadAgentFollowersIntent.Close)
            latestOpenAgentProfile(threadFollowerProfileIdentity(agent))
        }
    }
    SideEffect { owner?.value = FollowersOverlay(state, resolver, stableDispatch, openAgentProfile, agentStatusResolver) }
    DisposableEffect(owner) { onDispose { owner?.value = null } }
    if (agents.isEmpty() && !state.open && state.error != ThreadFollowersError.Load) return
    // Keep the live anchor while an empty popover is open, but never leave an empty trigger.
    Box(Modifier.raftCaptureLiveRootAnchor(anchorRef)) {
        if (agents.isNotEmpty() || state.error == ThreadFollowersError.Load) {
            val description = stringResource(Res.string.thread_followers_count, agents.size)
            ThreadHeaderActionButton(
                modifier = Modifier.semantics { contentDescription = description },
                horizontalPadding = 6.dp,
                onClick = {
                    resolver = anchorRef.resolver(RaftPopoverLocalPoint.BottomEnd)
                    if (resolver?.resolve() != null) dispatch(ThreadAgentFollowersIntent.Open)
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state.error == ThreadFollowersError.Load && agents.isEmpty()) {
                        RaftLucideIcon(RaftLucideIconName.Info, Modifier.size(16.dp))
                    } else {
                        val visibleAvatars = agents.take(3)
                        // Explicit offsets: negative Row spacing does not overlap native Kuikly children.
                        Box(Modifier.width((20 + (visibleAvatars.size - 1) * 14).dp).height(20.dp)) {
                            visibleAvatars.forEachIndexed { index, agent ->
                                Box(Modifier.offset(x = (index * 14).dp).zIndex(index.toFloat())) {
                                    FollowerAvatar(agent, compact = true)
                                }
                            }
                        }
                        // Web `font-mono text-mono-meta`.
                        RaftText(agents.size.toString(), style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foreground, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun FollowersPopover(
    state: ThreadAgentFollowersState,
    anchor: RaftPopoverAnchor,
    dispatch: (ThreadAgentFollowersIntent) -> Unit,
    onOpenAgentProfile: (ThreadAgentFollower) -> Unit,
    agentStatusResolver: RaftAgentStatusResolver,
) {
    val agents = state.roster?.agents.orEmpty()
    val density = LocalDensity.current
    val width = 288.dp
    // Real measured height replaces this estimate after the first layout.
    var measuredSize by remember { mutableStateOf(Size.Zero) }
    val estimatedHeight = (44 + if (agents.isEmpty()) 80 else 8 + agents.take(5).size * 64).dp
    val size = if (measuredSize != Size.Zero) measuredSize else with(density) { Size(width.toPx(), estimatedHeight.toPx()) }
    RaftAnchoredPopover(
        anchor = anchor, contentSize = size,
        onDismiss = { dispatch(ThreadAgentFollowersIntent.Close) },
        // Hangs from the trigger like the web's menu (its right edge under the avatar chip's),
        // not centred in the window: the window can be a wide desktop one (artin, 10-29).
        horizontalOffset = -size.width,
        horizontalPlacement = RaftFloatingHorizontalPlacement.Anchor,
        verticalOffset = with(density) { 6.dp.toPx() },
        viewportGap = with(density) { 8.dp.toPx() },
        verticalPlacement = RaftFloatingVerticalPlacement.Auto,
        dismissActivationDelayMillis = 0L,
    ) {
        Box(Modifier.onSizeChanged { measuredSize = Size(it.width.toFloat(), it.height.toFloat()) }) {
            ThreadAgentFollowersPanel(state, dispatch, onOpenAgentProfile, agentStatusResolver, width)
        }
    }
}

/** The followers popover's panel (web ThreadAgentFollowers `PopoverContent`), without its positioning. */
@Composable
internal fun ThreadAgentFollowersPanel(
    state: ThreadAgentFollowersState,
    dispatch: (ThreadAgentFollowersIntent) -> Unit,
    onOpenAgentProfile: (ThreadAgentFollower) -> Unit,
    agentStatusResolver: RaftAgentStatusResolver,
    width: Dp = 288.dp,
) {
    val agents = state.roster?.agents.orEmpty()
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    // raft-ui Popover (`w-72 p-0`): the popover layer, a seam ring, the float shadow.
    RaftPopoverSurface(
        modifier = Modifier.clickable { },
        width = width,
        contentPadding = PaddingValues(0.dp),
        header = {
            // `flex min-h-11 items-center border-b border-line-seam px-4 text-headline`
            Box(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                RaftText(stringResource(Res.string.thread_followers_title), style = typography.headline, color = colors.foreground)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.lineSeam))
        },
    ) {
        if (state.loading && agents.isEmpty()) {
            RaftText(
                stringResource(Res.string.thread_followers_loading),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                style = typography.callout,
                color = colors.foregroundHint,
                textAlign = TextAlign.Center,
            )
        } else if (state.error == ThreadFollowersError.Load) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RaftLucideIcon(RaftLucideIconName.TriangleAlert, Modifier.size(18.dp), color = raftArgbHex(colors.foregroundMuted))
                RaftText(
                    stringResource(Res.string.thread_followers_load_failed),
                    style = typography.callout,
                    color = colors.foregroundMuted,
                    textAlign = TextAlign.Center,
                )
                RaftButton(
                    text = stringResource(Res.string.common_retry),
                    onClick = { dispatch(ThreadAgentFollowersIntent.Refresh) },
                    size = RaftButtonSize.Md,
                    enabled = !state.loading,
                )
            }
        } else if (agents.isEmpty()) {
            RaftText(
                stringResource(Res.string.thread_followers_empty),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                style = typography.callout,
                color = colors.foregroundHint,
                textAlign = TextAlign.Center,
            )
        } else {
            // `max-h-72 overflow-y-auto p-2`
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 288.dp), contentPadding = PaddingValues(8.dp)) {
                items(agents, key = { it.id }) { agent ->
                    val server = agent.serverName?.takeIf { it.isNotBlank() }
                        ?: agent.serverSlug?.takeIf { it.isNotBlank() }
                        ?: stringResource(Res.string.thread_followers_peer_server)
                    val canManage = state.roster?.canManage == true
                    // The server decides (#13093): in a joint channel the host's admins and channel
                    // managers can remove another server's agent without switching servers.
                    val canRemove = agent.canRemove
                    // Web: the X names the action, or why a peer server's agent cannot be removed here.
                    val removeLabel =
                        if (canRemove || agent.isCurrentServer) stringResource(Res.string.thread_followers_remove, agent.label)
                        else stringResource(Res.string.thread_followers_peer_remove_host_only)
                    // Task #20: the same live source (and external-agent tiering) as the
                    // message avatar; collected as state so the row follows it while open.
                    val indicator = threadFollowerStatusIndicator(
                        agent,
                        rememberRaftAgentStatusIndicatorState(
                            threadFollowerStatusResolver(agent, agentStatusResolver), agent.id,
                        ),
                    )
                    // raft-ui ListRow (web ChannelMemberRow with onRowClick): the whole row opens
                    // the profile and shows the pressed overlay; the remove X is its own ghost button.
                    RaftListRow(
                        title = agent.label,
                        onClick = { onOpenAgentProfile(agent) },
                        subtitle = indicator?.localizedMemberStatusLabel(),
                        // Web ChannelMemberRow: the members page's type (the activity in the sans face).
                        text = RaftListRowText.Member,
                        leading = {
                            RaftAvatarWithStatus(
                                statusColor = indicator?.color,
                                avatarSize = RaftAvatarSize.Md.edge,
                                statusPulsing = indicator?.pulsing == true,
                                cutoutColor = colors.layerPopover,
                            ) {
                                RaftMemberAvatar(
                                    identity = agent.avatarIdentity(),
                                    label = agent.label,
                                    avatarSize = RaftAvatarSize.Md.edge,
                                    carriedAvatarWireValue = agent.avatarUrl,
                                )
                            }
                        },
                        // Web JointPeerBadge: a peer server's agent names its server right after the name.
                        titleTrailing = if (agent.isCurrentServer) null else {
                            {
                                // Web `max-w-1/2`: the name keeps at least half the line; the server truncates first.
                                RaftJointServerBadge(server, modifier = Modifier.widthIn(max = 72.dp))
                            }
                        },
                        trailing = if (canManage) {
                            {
                                // The web's 14px X; the box stays 44dp, a finger's target next to the row.
                                RaftIconButton(
                                    onClick = { dispatch(ThreadAgentFollowersIntent.Remove(agent.id)) },
                                    tooltip = removeLabel,
                                    variant = RaftButtonVariant.Ghost,
                                    size = RaftIconButtonSize.Lg,
                                    enabled = threadFollowerRemoveEnabled(agent, state),
                                ) {
                                    RaftGlyphIcon(RaftGlyph.X, size = 14.dp)
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

/** The member detail a follower row opens: the agent on its own server (peer agents read-only). */
internal fun threadFollowerProfileIdentity(agent: ThreadAgentFollower): MemberDetailIdentity =
    MemberDetailIdentity(
        memberId = MemberId(agent.id),
        kind = MemberKind.Agent,
        sourceServerId = agent.serverId.trim().takeIf { it.isNotBlank() }?.let(::ServerId),
    )

/** Web: removable when the server says so (`canRemove`), and not while a removal (or its undo window) is pending. */
internal fun threadFollowerRemoveEnabled(agent: ThreadAgentFollower, state: ThreadAgentFollowersState): Boolean =
    state.roster?.canManage == true &&
        agent.canRemove &&
        !state.mutating && state.undo == null

@Composable
private fun FollowerAvatar(agent: ThreadAgentFollower, compact: Boolean) {
    RaftMemberAvatar(
        identity = agent.avatarIdentity(),
        label = agent.label,
        avatarSize = if (compact) 20.dp else 28.dp,
        carriedAvatarWireValue = agent.avatarUrl,
    )
}

/** A follower's face is the agent's, read from its own server's directory. */
private fun ThreadAgentFollower.avatarIdentity(): RaftMemberAvatarIdentity =
    RaftMemberAvatarIdentity(kind = MemberKind.Agent, memberId = id, serverId = serverId)

/**
 * Task #20: the followers endpoint's `status` is the agent's DB lifecycle column (active /
 * inactive / stopped), not its live activity, and it is a one-shot snapshot — so it never
 * drives the dot or the caption. Every follower reads the live member-status source the
 * thread's message avatars read: a peer server's agent in a joint channel too, from the
 * channel's members (artin 2026-11-02: the web shows "Message received" under a peer agent;
 * the app showed nothing).
 */
@Suppress("UNUSED_PARAMETER")
internal fun threadFollowerStatusResolver(
    agent: ThreadAgentFollower,
    liveResolver: RaftAgentStatusResolver,
): RaftAgentStatusResolver = liveResolver

/** The follower row's indicator; null (no dot, no caption) until the live source has the agent. */
@Suppress("UNUSED_PARAMETER")
internal fun threadFollowerStatusIndicator(
    agent: ThreadAgentFollower,
    live: RaftAgentStatusIndicatorState?,
): RaftAgentStatusIndicatorState? = live
