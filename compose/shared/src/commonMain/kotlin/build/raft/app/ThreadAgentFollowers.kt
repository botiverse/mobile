package build.raft.app

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
import build.raft.app.model.AgentStatus
import build.raft.app.base.Utils
import build.raft.app.data.RealThreadAgentFollowersRepository
import build.raft.app.data.RealtimeGuardedActiveThreadSink
import build.raft.app.data.SharedThreadAgentFollowersStore
import build.raft.app.data.SlockSharedStoreRegistry
import build.raft.app.data.SlockShellStoreScope
import build.raft.app.data.ThreadAgentFollower
import build.raft.app.data.ThreadAgentFollowersIntent
import build.raft.app.data.ThreadAgentFollowersState
import build.raft.app.data.ThreadFollowersError
import build.raft.app.model.ThreadFollowersUpdated
import build.raft.app.resources.MR
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
import androidx.compose.ui.unit.dp
import build.raft.app.compat.pager.Pager
import com.tencent.tmm.kmmresource.compose.stringResource

@Composable
internal fun ThreadAgentFollowersRoute(
    threadId: String?, serverId: String?, accountId: String?,
    storeScope: SlockShellStoreScope, active: Boolean,
) {
    if (threadId.isNullOrBlank() || serverId.isNullOrBlank() || accountId.isNullOrBlank()) return
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager) { Utils.bridgeModule(pager.pagerId) }
    val shared = remember(bridge) { SlockSharedStoreRegistry.get(bridge) }
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
    ThreadAgentFollowersHeader(state, store::dispatch)
}

private data class FollowersOverlay(
    val state: ThreadAgentFollowersState,
    val anchor: SlockPopoverAnchorResolver?,
    val dispatch: (ThreadAgentFollowersIntent) -> Unit,
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
                if (anchor != null) FollowersPopover(overlay.state, anchor, overlay.dispatch)
                overlay.state.notice?.let { notice ->
                    FollowerNoticeView(
                        notice = notice,
                        canUndo = notice.kind == ThreadFollowersNoticeKind.Removed && overlay.state.undo != null,
                        enabled = !overlay.state.mutating,
                        onUndo = { overlay.dispatch(ThreadAgentFollowersIntent.Undo) },
                        modifier = Modifier.align(Alignment.BottomCenter).zIndex(SlockFloatingOverlayHostZIndex + 1f),
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
    val theme = LocalSlockTheme.current
    val brutal = !theme.isAmbient
    val isError = notice.kind == ThreadFollowersNoticeKind.RemoveFailed || notice.kind == ThreadFollowersNoticeKind.RestoreFailed
    val label = stringResource(when (notice.kind) {
        ThreadFollowersNoticeKind.Removed -> MR.strings.thread_followers_removed
        ThreadFollowersNoticeKind.Restored -> MR.strings.thread_followers_restored
        ThreadFollowersNoticeKind.RemoveFailed -> MR.strings.thread_followers_remove_failed
        ThreadFollowersNoticeKind.RestoreFailed -> MR.strings.thread_followers_restore_failed
    }, notice.agentLabel)
    val shape = RoundedCornerShape(if (brutal) 0.dp else 12.dp)
    val surface = if (brutal) {
        if (isError) SlockColor.AccentOrange else SlockColor.AccentLime
    } else Color(0xED2C2C2E)
    val ink = if (brutal) SlockColor.TextBlack else SlockColor.SurfaceWhite
    Box(modifier.padding(16.dp).widthIn(max = 384.dp)) {
        if (brutal) Box(Modifier.matchParentSize().offset(4.dp, 4.dp).background(SlockColor.ShadowBlack))
        Column(
            Modifier.fillMaxWidth().background(surface, shape)
                .border(if (brutal) 2.dp else 1.dp, if (brutal) theme.line else Color.White.copy(alpha = 0.2f), shape)
                .slockNativeDispatchCapture().clickable { }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (brutal) 6.dp else 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SlockLucideIcon(if (isError) SlockLucideIconName.Info else SlockLucideIconName.CircleCheck,
                    Modifier.size(16.dp), color = if (brutal) SlockColor.TextBlackArgb else SlockColor.SurfaceWhiteArgb)
                Text(label, style = SlockType.Caption.copy(fontSize = 14.sp, lineHeight = 20.sp,
                    fontWeight = if (brutal) FontWeight.Bold else FontWeight.Medium), color = ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            if (canUndo) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    val buttonShape = RoundedCornerShape(if (brutal) 0.dp else 100.dp)
                    Box(Modifier.heightIn(min = 28.dp)
                        .background(if (brutal) SlockColor.TextBlack else Color.White.copy(alpha = 0.06f), buttonShape)
                        .border(if (brutal) 2.dp else 1.dp, if (brutal) theme.line else Color.White.copy(alpha = 0.14f), buttonShape)
                        .clickable(enabled = enabled, onClick = onUndo).padding(horizontal = 8.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(MR.strings.common_undo), style = SlockType.Caption, color = SlockColor.SurfaceWhite)
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
) {
    val agents = state.roster?.agents.orEmpty()
    val owner = LocalFollowersOverlay.current
    val anchorRef = remember { SlockLiveRootAnchorRef("thread-followers") }
    var resolver by remember { mutableStateOf<SlockPopoverAnchorResolver?>(null) }
    val latestDispatch by rememberUpdatedState(dispatch)
    val stableDispatch = remember { { intent: ThreadAgentFollowersIntent -> latestDispatch(intent) } }
    SideEffect { owner?.value = FollowersOverlay(state, resolver, stableDispatch) }
    DisposableEffect(owner) { onDispose { owner?.value = null } }
    if (agents.isEmpty() && !state.open && state.error != ThreadFollowersError.Load) return
    // Keep the live anchor while an empty popover is open, but never leave an empty trigger.
    Box(Modifier.slockCaptureLiveRootAnchor(anchorRef)) {
        if (agents.isNotEmpty() || state.error == ThreadFollowersError.Load) {
            val description = stringResource(MR.strings.thread_followers_count, agents.size)
            ThreadHeaderActionButton(
                modifier = Modifier.semantics { contentDescription = description },
                horizontalPadding = 6.dp,
                onClick = {
                    resolver = anchorRef.resolver(SlockPopoverLocalPoint.BottomEnd)
                    if (resolver?.resolve() != null) dispatch(ThreadAgentFollowersIntent.Open)
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state.error == ThreadFollowersError.Load && agents.isEmpty()) {
                        SlockLucideIcon(SlockLucideIconName.Info, Modifier.size(16.dp))
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
                        Text(agents.size.toString(), style = SlockType.Caption.copy(fontWeight = FontWeight.Bold), color = SlockColor.TextBlack, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun FollowersPopover(state: ThreadAgentFollowersState, anchor: SlockPopoverAnchor, dispatch: (ThreadAgentFollowersIntent) -> Unit) {
    val agents = state.roster?.agents.orEmpty()
    val theme = LocalSlockTheme.current
    val density = LocalDensity.current
    var peerHint by remember { mutableStateOf<String?>(null) }
    val width = 288.dp
    // Real measured height replaces this estimate after the first layout.
    var measuredSize by remember { mutableStateOf(Size.Zero) }
    val estimatedHeight = (44 + if (agents.isEmpty()) 80 else agents.take(6).size * 48).dp
    val size = if (measuredSize != Size.Zero) measuredSize else with(density) { Size(width.toPx(), estimatedHeight.toPx()) }
    SlockAnchoredPopover(
        anchor = anchor, contentSize = size,
        onDismiss = { dispatch(ThreadAgentFollowersIntent.Close) },
        horizontalOffset = 0f,
        horizontalPlacement = SlockFloatingHorizontalPlacement.Center,
        verticalOffset = with(density) { 6.dp.toPx() },
        viewportGap = with(density) { 8.dp.toPx() },
        verticalPlacement = SlockFloatingVerticalPlacement.Auto,
        dismissActivationDelayMillis = 0L,
    ) {
        Box(Modifier.onSizeChanged { measuredSize = Size(it.width.toFloat(), it.height.toFloat()) }) {
        val shape = RoundedCornerShape(theme.cornerRadius)
        if (!theme.isAmbient) {
            Box(Modifier.matchParentSize().offset(4.dp, 4.dp).background(theme.shadow.color))
        }
        Column(Modifier.width(width)
            .then(if (theme.isAmbient) Modifier.shadow(4.dp, shape, clip = false,
                ambientColor = theme.shadow.color, spotColor = theme.shadow.color) else Modifier)
            .background(theme.surface, shape)
            .border(theme.lineWidth, theme.line, RoundedCornerShape(theme.cornerRadius)).clickable { }) {
            Box(Modifier.fillMaxWidth()
                .background(if (!theme.isAmbient) SlockColor.SurfaceCream else theme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(stringResource(MR.strings.thread_followers_title),
                    style = SlockType.Caption.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold), color = theme.ink)
            }
            Box(Modifier.fillMaxWidth().height(theme.lineWidth).background(theme.line))
            if (state.loading && agents.isEmpty()) {
                Box(Modifier.padding(16.dp)) {
                    Text(stringResource(MR.strings.thread_followers_loading), style = SlockType.Caption, color = theme.inkMuted)
                }
            } else if (state.error == ThreadFollowersError.Load) {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(MR.strings.thread_followers_load_failed), style = SlockType.Caption, color = theme.inkMuted)
                    Box(Modifier.clickable(enabled = !state.loading) { dispatch(ThreadAgentFollowersIntent.Refresh) }.padding(8.dp)) {
                        Text(stringResource(MR.strings.common_retry), style = SlockType.Caption, color = theme.ink)
                    }
                }
            } else if (agents.isEmpty()) {
                Box(Modifier.padding(16.dp)) {
                    Text(stringResource(MR.strings.thread_followers_empty), style = SlockType.Caption, color = theme.inkMuted)
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(horizontal = 12.dp, vertical = 12.dp)) {
                    items(agents, key = { it.id }) { agent ->
                        val server = agent.serverName?.takeIf { it.isNotBlank() }
                            ?: agent.serverSlug?.takeIf { it.isNotBlank() }
                            ?: stringResource(MR.strings.thread_followers_peer_server)
                        val peerExplanation = stringResource(MR.strings.thread_followers_peer_remove_unavailable, server)
                        val canManage = state.roster?.canManage == true
                        val canRemove = agent.canRemove && agent.isCurrentServer
                        val indicator = followerStatusIndicator(agent.status)
                        // Sharing member-row presentation must not replace follower-specific permissions.
                        SlockModalCompactMemberRow(
                            title = agent.label,
                            subtitle = indicator?.localizedMemberStatusLabel() ?: agent.status,
                            avatarWireValue = agent.avatarUrl,
                            avatarLabel = agent.label,
                            statusColor = indicator?.color,
                            statusPulsing = indicator?.pulsing == true,
                            cacheServerId = agent.serverId,
                            sourceBadgeLabel = server.takeUnless { agent.isCurrentServer },
                            clickEnabled = false,
                            trailingIcon = SlockLucideIconName.X.takeIf { canManage },
                            trailingMuted = !canRemove,
                            onTrailingClick = if (canManage) ({
                                if (!state.mutating && (state.undo == null || !canRemove)) {
                                    if (canRemove) dispatch(ThreadAgentFollowersIntent.Remove(agent.id))
                                    else peerHint = peerExplanation
                                }
                            }) else null,
                        )
                    }
                }
                peerHint?.let { hint ->
                    Box(Modifier.fillMaxWidth().background(theme.surfaceMuted).padding(12.dp).clickable { peerHint = null }) {
                        Text(hint, style = SlockType.Caption, color = theme.inkMuted)
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun FollowerAvatar(agent: ThreadAgentFollower, compact: Boolean) {
    SlockAvatar(avatarWireValue = agent.avatarUrl, label = agent.label,
        avatarSize = if (compact) 20.dp else 28.dp, cacheServerId = agent.serverId)
}

/** The follower endpoint carries display status, not external-agent execution mode. */
internal fun followerStatusIndicator(wireStatus: String): SlockAgentStatusIndicatorState? =
    AgentStatus.fromWireValue(wireStatus)?.let {
        slockAgentStatusIndicatorState(it, it.presence, external = false)
    }
