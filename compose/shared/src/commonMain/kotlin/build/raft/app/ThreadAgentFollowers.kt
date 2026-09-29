package build.raft.app

import androidx.compose.runtime.Composable
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

@Composable
internal fun ThreadAgentFollowersHeader(
    state: ThreadAgentFollowersState,
    dispatch: (ThreadAgentFollowersIntent) -> Unit,
) {
    val agents = state.roster?.agents.orEmpty()
    if (agents.isNotEmpty()) {
        Row(
            modifier = Modifier.clickable { dispatch(ThreadAgentFollowersIntent.Open) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            agents.take(3).forEach { FollowerAvatar(it, compact = true) }
            Text(
                stringResource(MR.strings.thread_followers_count, agents.size),
                style = SlockType.Caption, color = SlockColor.TextMutedGray,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    } else if (state.error == ThreadFollowersError.Load && state.roster?.disabled != true) {
        Text(
            stringResource(MR.strings.thread_followers_retry), style = SlockType.Caption,
            color = SlockColor.TextMutedGray,
            modifier = Modifier.clickable { dispatch(ThreadAgentFollowersIntent.Open) }.padding(vertical = 4.dp),
        )
    }
    // Independent of the trigger: removing the final follower must not remove Undo.
    if (state.undo != null && !state.open) FollowerUndoRow(state, dispatch)
    if (state.open) {
        SlockUnifiedDialog(
            title = stringResource(MR.strings.thread_followers_title),
            titleFontSize = 18, titleLineHeight = 22,
            onDismiss = { dispatch(ThreadAgentFollowersIntent.Close) },
        ) {
            if (state.loading) Text(stringResource(MR.strings.thread_followers_loading), style = SlockType.Caption)
            state.error?.let { error ->
                Text(stringResource(when (error) {
                    ThreadFollowersError.Load -> MR.strings.thread_followers_load_failed
                    ThreadFollowersError.Remove -> MR.strings.thread_followers_remove_failed
                    ThreadFollowersError.Restore -> MR.strings.thread_followers_restore_failed
                }), style = SlockType.Caption)
                if (error == ThreadFollowersError.Load) SlockModalActionButton(
                    label = stringResource(MR.strings.common_retry), enabled = !state.loading,
                    onClick = { dispatch(ThreadAgentFollowersIntent.Refresh) },
                )
            }
            if (agents.isEmpty() && !state.loading && state.error == null) {
                Text(stringResource(MR.strings.thread_followers_empty), style = SlockType.Caption)
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(agents, key = { it.id }) { agent ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FollowerAvatar(agent, compact = false)
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(agent.label, style = SlockType.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!agent.isCurrentServer) {
                                Text(
                                    agent.serverName?.takeIf { it.isNotBlank() }
                                        ?: stringResource(MR.strings.thread_followers_peer_server),
                                    style = SlockType.Caption, color = SlockColor.TextMutedGray,
                                    modifier = Modifier.background(SlockColor.AccentOrange.copy(alpha = 0.2f))
                                        .padding(horizontal = 5.dp, vertical = 2.dp),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (state.roster?.canManage == true && agent.canRemove && agent.isCurrentServer) {
                            SlockModalActionButton(
                                label = stringResource(MR.strings.common_remove),
                                enabled = !state.mutating && state.undo == null,
                                onClick = { dispatch(ThreadAgentFollowersIntent.Remove(agent.id)) },
                            )
                        }
                    }
                }
            }
            FollowerUndoRow(state, dispatch)
        }
    }
}

@Composable
private fun FollowerAvatar(agent: ThreadAgentFollower, compact: Boolean) {
    SlockAvatar(
        avatarWireValue = agent.avatarUrl, label = agent.label,
        avatarSize = if (compact) 20.dp else 32.dp,
        cacheServerId = agent.serverId,
    )
}

@Composable
private fun FollowerUndoRow(state: ThreadAgentFollowersState, dispatch: (ThreadAgentFollowersIntent) -> Unit) {
    if (state.undo == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(MR.strings.thread_followers_removed), style = SlockType.Caption)
        SlockModalActionButton(
            label = stringResource(MR.strings.common_undo), enabled = !state.mutating,
            onClick = { dispatch(ThreadAgentFollowersIntent.Undo) },
        )
    }
}
