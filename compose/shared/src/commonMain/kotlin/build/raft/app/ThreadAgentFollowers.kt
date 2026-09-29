package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import build.raft.app.data.ThreadFollowersNoticeKind
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.layout.onSizeChanged
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.semantics.contentDescription
import com.tencent.kuikly.compose.ui.semantics.semantics
import com.tencent.kuikly.compose.ui.zIndex
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.lazy.items
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.pager.Pager
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
                    val isError = notice.kind == ThreadFollowersNoticeKind.RemoveFailed || notice.kind == ThreadFollowersNoticeKind.RestoreFailed
                    val label = stringResource(when (notice.kind) {
                        ThreadFollowersNoticeKind.Removed -> MR.strings.thread_followers_removed
                        ThreadFollowersNoticeKind.Restored -> MR.strings.thread_followers_restored
                        ThreadFollowersNoticeKind.RemoveFailed -> MR.strings.thread_followers_remove_failed
                        ThreadFollowersNoticeKind.RestoreFailed -> MR.strings.thread_followers_restore_failed
                    }, notice.agentLabel)
                    val theme = LocalSlockTheme.current
                    Row(
                        modifier = Modifier.align(Alignment.BottomCenter).zIndex(SlockFloatingOverlayHostZIndex + 1f)
                            .padding(horizontal = 16.dp, vertical = 24.dp).widthIn(max = 360.dp)
                            .background(theme.surface, RoundedCornerShape(theme.cornerRadius))
                            .border(theme.lineWidth, theme.line, RoundedCornerShape(theme.cornerRadius))
                            .slockNativeDispatchCapture().clickable { }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SlockLucideIcon(if (isError) SlockLucideIconName.Info else SlockLucideIconName.CircleCheck, Modifier.size(16.dp))
                        Text(label, style = SlockType.Caption, color = theme.ink, modifier = Modifier.weight(1f))
                        if (notice.kind == ThreadFollowersNoticeKind.Removed && overlay.state.undo != null) {
                            Text(stringResource(MR.strings.common_undo), style = SlockType.Caption, color = theme.ink,
                                modifier = Modifier.clickable(enabled = !overlay.state.mutating) { overlay.dispatch(ThreadAgentFollowersIntent.Undo) }.padding(6.dp))
                        }
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
    // Keep the live anchor while an empty popover is open, but never leave an empty trigger.
    Box(Modifier.slockCaptureLiveRootAnchor(anchorRef)) {
        if (agents.isNotEmpty() || state.error == ThreadFollowersError.Load) {
            val theme = LocalSlockTheme.current
            val description = stringResource(MR.strings.thread_followers_count, agents.size)
            Row(
                modifier = Modifier.height(28.dp)
                    .border(theme.lineWidth, theme.line, RoundedCornerShape(theme.cornerRadius))
                    .semantics { contentDescription = description }
                    .clickable {
                        resolver = anchorRef.resolver(SlockPopoverLocalPoint.BottomEnd)
                        if (resolver?.resolve() != null) dispatch(ThreadAgentFollowersIntent.Open)
                    }.padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (state.error == ThreadFollowersError.Load && agents.isEmpty()) {
                    SlockLucideIcon(SlockLucideIconName.Info, Modifier.size(16.dp))
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy((-4).dp)) {
                        agents.take(3).forEach { FollowerAvatar(it, compact = true) }
                    }
                    Text(agents.size.toString(), style = SlockType.Caption, color = theme.ink, maxLines = 1)
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
    val width = 288.dp
    // Real measured height replaces this estimate after the first layout.
    var measuredSize by remember { mutableStateOf(Size.Zero) }
    val estimatedHeight = (44 + if (agents.isEmpty()) 80 else agents.take(6).sumOf { if (it.isCurrentServer) 48 else 80 }).dp
    val size = if (measuredSize != Size.Zero) measuredSize else with(density) { Size(width.toPx(), estimatedHeight.toPx()) }
    SlockAnchoredPopover(
        anchor = anchor, contentSize = size,
        onDismiss = { dispatch(ThreadAgentFollowersIntent.Close) },
        horizontalOffset = -size.width,
        verticalOffset = with(density) { 6.dp.toPx() },
        viewportGap = with(density) { 8.dp.toPx() },
        verticalPlacement = SlockFloatingVerticalPlacement.Auto,
        dismissActivationDelayMillis = 0L,
    ) {
        Column(Modifier.width(width).onSizeChanged { measuredSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .background(theme.surface, RoundedCornerShape(theme.cornerRadius))
            .border(theme.lineWidth, theme.line, RoundedCornerShape(theme.cornerRadius)).clickable { }) {
            Text(stringResource(MR.strings.thread_followers_title), style = SlockType.Body, color = theme.ink,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp))
            if (state.loading && agents.isEmpty()) {
                Text(stringResource(MR.strings.thread_followers_loading), style = SlockType.Caption, color = theme.inkMuted, modifier = Modifier.padding(16.dp))
            } else if (state.error == ThreadFollowersError.Load) {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(MR.strings.thread_followers_load_failed), style = SlockType.Caption, color = theme.inkMuted)
                    Text(stringResource(MR.strings.common_retry), style = SlockType.Caption, color = theme.ink,
                        modifier = Modifier.clickable(enabled = !state.loading) { dispatch(ThreadAgentFollowersIntent.Refresh) }.padding(8.dp))
                }
            } else if (agents.isEmpty()) {
                Text(stringResource(MR.strings.thread_followers_empty), style = SlockType.Caption, color = theme.inkMuted, modifier = Modifier.padding(16.dp))
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    items(agents, key = { it.id }) { agent ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FollowerAvatar(agent, compact = false)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(agent.label, style = SlockType.Caption, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!agent.isCurrentServer) {
                                    val server = agent.serverName?.takeIf { it.isNotBlank() } ?: agent.serverSlug?.takeIf { it.isNotBlank() } ?: stringResource(MR.strings.thread_followers_peer_server)
                                    Text(server, style = SlockType.Caption, color = theme.inkMuted,
                                        modifier = Modifier.background(theme.surfaceMuted).padding(horizontal = 4.dp, vertical = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(stringResource(MR.strings.thread_followers_peer_remove_unavailable, server), style = SlockType.Caption, color = theme.inkMuted)
                                }
                            }
                            if (state.roster?.canManage == true && agent.canRemove && agent.isCurrentServer) {
                                val removeLabel = stringResource(MR.strings.common_remove)
                                Box(Modifier.size(28.dp).semantics { contentDescription = removeLabel }
                                    .clickable(enabled = !state.mutating && state.undo == null) { dispatch(ThreadAgentFollowersIntent.Remove(agent.id)) }, contentAlignment = Alignment.Center) {
                                    SlockLucideIcon(SlockLucideIconName.X, Modifier.size(14.dp))
                                }
                            }
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
