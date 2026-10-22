package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import build.raft.app.data.PeerReadReceiptIntent
import build.raft.app.data.PeerReadReceiptOwner
import build.raft.app.data.PeerReadReceiptScope
import build.raft.app.data.READ_RECEIPTS_FEATURE_FLAG_KEY
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RealtimeGuardedActiveThreadSink
import build.raft.app.data.projectAgentReadReceipt
import build.raft.app.data.projectPeerReadReceipt
import build.raft.app.markdown.RaftMarkdownMentionReadStates
import build.raft.app.model.MemberKind
import build.raft.app.model.ScopeReadUpdated
import build.raft.app.model.ServerId
import build.raft.app.resources.MR
import com.tencent.tmm.kmmresource.compose.stringResource

/**
 * Read receipts (`read_receipts_v0`) as the web shows them (botiverse/slock staging dac70954,
 * `MessageItem.tsx` + `MentionLink.tsx`, task #693):
 *  - on the viewer's OWN message, each @mentioned agent's mention carries a corner pixel: lime once
 *    that agent has read the message, white until then; nothing for humans, non-peers or a
 *    summary-only (large) scope;
 *  - in a DM (and a DM thread), one right-aligned "Read" line under the conversation's last
 *    message, at the live edge, when it is the viewer's and the agent peer has read it.
 * Channels never show an aggregate "read by" marker (web regression guard). Other people's read
 * positions only: the viewer's own position is [build.raft.app.data.ReadReceiptCoordinator]'s.
 *
 * [scope] is the hydrated scope; the provider is null until the hydrate arrives (the gate).
 */
@Immutable
internal data class ThreadPeerReadReceiptsUi(
    val scope: PeerReadReceiptScope,
    val dmSurface: Boolean,
    /** Mention lookup key (lowercase, no `@`) -> agent id, for the agents this page knows. */
    val agentIdsByMentionKey: Map<String, String>,
)

internal val LocalThreadPeerReadReceipts = compositionLocalOf<ThreadPeerReadReceiptsUi?> { null }

internal fun threadMentionReadLookupKey(label: String): String = label.trim().removePrefix("@").trim().lowercase()

/** Web `agentReadBadgesEnabled`: only the human sender of the message sees its badges. */
private fun RaftThreadMessageItem.showsPeerReadReceipts(): Boolean =
    isOwn && authorKind != "agent" && (seq ?: 0L) > 0L

/** The @mention badge states for [message]'s body, or null when it gets none. */
internal fun ThreadPeerReadReceiptsUi.mentionReadStates(message: RaftThreadMessageItem): RaftMarkdownMentionReadStates? {
    if (!message.showsPeerReadReceipts() || scope !is PeerReadReceiptScope.Peers) return null
    val seq = message.seq
    return RaftMarkdownMentionReadStates { label ->
        agentIdsByMentionKey[threadMentionReadLookupKey(label)]?.let { agentId ->
            projectAgentReadReceipt(scope, agentId, seq)
        }
    }
}

/** Web `showDmReadReceipt`, given that [message] is the DM's last message at the live edge. */
internal fun ThreadPeerReadReceiptsUi.showsDmReadStatus(message: RaftThreadMessageItem): Boolean =
    dmSurface && message.showsPeerReadReceipts() && projectPeerReadReceipt(scope, message.seq).read

/** Web ChatPanel/ThreadPanel `dm*ReadStatusMessageId`: the last loaded message, only at the live edge. */
internal fun ThreadTimelineSlice.dmReadStatusMessageId(receipts: ThreadPeerReadReceiptsUi?): String? =
    if (receipts?.dmSurface == true && !canLoadMoreAfter) messages.lastOrNull()?.id else null

internal fun RaftThreadScreenPayload.isPeerReadReceiptDmSurface(): Boolean =
    surfaceKind == RaftThreadSurfaceKind.DirectMessage ||
        channelKind == "dm" ||
        (surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
            (parentSurfaceKind == RaftThreadSurfaceKind.DirectMessage || parentChannelKind == "dm"))

/**
 * Web `isReadReceiptScopeEligible`: a thread is its own scope (the server decides from its parent);
 * `#all` and joint channels never carry receipts, so they are not even fetched.
 */
internal fun RaftThreadScreenPayload.peerReadReceiptScopeId(): String? {
    val scopeId =
        (streamChannelId?.trim()?.takeIf { it.isNotBlank() } ?: sourceThreadId?.trim()?.takeIf { it.isNotBlank() })
            ?: return null
    if (surfaceKind == RaftThreadSurfaceKind.ReplyThread) return scopeId
    if (channelKind == JointChannelKindWireValue) return null
    if (surfaceKind == RaftThreadSurfaceKind.Channel && title.trim().removePrefix("#") == "all") return null
    return scopeId
}

private fun RaftThreadScreenPayload.agentIdsByMentionKey(): Map<String, String> =
    buildMap {
        (members + memberCandidates)
            .filter { it.kind == "agent" && it.id.isNotBlank() }
            .forEach { member ->
                listOf(member.id, member.handle, member.displayName)
                    .map(::threadMentionReadLookupKey)
                    .filter { it.isNotBlank() }
                    .forEach { key -> putIfAbsentCompat(key, member.id) }
            }
        val peer = dmPeerIdentity
        if (peer != null && peer.kind == MemberKind.Agent) {
            listOfNotNull(peer.memberId.value, dmPeerName)
                .map(::threadMentionReadLookupKey)
                .filter { it.isNotBlank() }
                .forEach { key -> putIfAbsentCompat(key, peer.memberId.value) }
        }
    }

// Map.putIfAbsent is JVM-only; commonMain must also compile for Kotlin/Native.
private fun MutableMap<String, String>.putIfAbsentCompat(key: String, value: String) {
    if (key !in this) put(key, value)
}

/**
 * Binds the page to the shell's read-receipt store: hydrates the page's scope once its first page
 * settled (web waits for the same), applies `scope_read:updated` frames for it, and clears every
 * scope when the flag is off (web `useReadReceiptHydrate`). Returns the UI projection, or null while
 * the scope has no hydrate.
 */
@Composable
internal fun rememberThreadPeerReadReceipts(
    payload: RaftThreadScreenPayload,
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    serverId: String?,
    accountId: String?,
    active: Boolean,
): ThreadPeerReadReceiptsUi? {
    val store = remember(sharedStores, storeScope) { sharedStores.peerReadReceiptStore(storeScope) }
    val environment by sharedStores.apiEnvironmentStore.state.collectAsState()
    val flagRevision by sharedStores.serverFeatureFlags.snapshotRevision.collectAsState()
    val owner =
        remember(environment.apiBaseUrl, accountId, serverId) {
            val environmentId = environment.apiBaseUrl.trim().trimEnd('/')
            val account = accountId?.trim().orEmpty()
            val server = serverId?.trim().orEmpty()
            if (environmentId.isBlank() || account.isBlank() || server.isBlank()) {
                null
            } else {
                PeerReadReceiptOwner(environmentId, account, server)
            }
        }
    val enabled =
        remember(owner, flagRevision) {
            owner != null && sharedStores.serverFeatureFlags.isEnabled(ServerId(owner.serverId), READ_RECEIPTS_FEATURE_FLAG_KEY)
        }
    val scopeId = payload.peerReadReceiptScopeId()
    val firstPageSettled = payload.status != "loading"
    LaunchedEffect(store, owner) { store.dispatch(PeerReadReceiptIntent.BindOwner(owner)) }
    LaunchedEffect(store, owner, enabled, scopeId, firstPageSettled, active) {
        val currentOwner = owner ?: return@LaunchedEffect
        if (!enabled) {
            store.dispatch(PeerReadReceiptIntent.ClearAll)
            return@LaunchedEffect
        }
        if (scopeId != null && firstPageSettled && active) store.hydrate(currentOwner, scopeId)
    }
    val realtimeOwner = remember(sharedStores, storeScope) { sharedStores.realtimeDomainEventOwner(storeScope) }
    DisposableEffect(realtimeOwner, store, owner, scopeId, enabled) {
        val currentOwner = owner
        val sink =
            RealtimeGuardedActiveThreadSink { eventServerId, event ->
                if (
                    enabled && currentOwner != null && event is ScopeReadUpdated &&
                    eventServerId == currentOwner.serverId && event.scopeId == scopeId
                ) {
                    store.consumeScopeUpdated(currentOwner, event)
                }
            }
        realtimeOwner.registerGuardedActiveThreadSink(sink)
        onDispose { realtimeOwner.unregisterGuardedActiveThreadSink(sink) }
    }
    val state by store.state.collectAsState()
    val scope = if (enabled && state.owner == owner && scopeId != null) state.scopes[scopeId] else null
    val dmSurface = payload.isPeerReadReceiptDmSurface()
    val mentionKeys = remember(payload.members, payload.memberCandidates, payload.dmPeerIdentity, payload.dmPeerName) {
        payload.agentIdsByMentionKey()
    }
    return remember(scope, dmSurface, mentionKeys) {
        scope?.let { ThreadPeerReadReceiptsUi(scope = it, dmSurface = dmSurface, agentIdsByMentionKey = mentionKeys) }
    }
}

/**
 * Web `dm-read-status` (artin, option C): its own right-aligned line under the DM's last message,
 * below everything the message owns — a 12dp circle-check and "Read" in caption / foreground-hint,
 * on a 20dp line.
 */
@Composable
internal fun ThreadDmReadStatusRow(modifier: Modifier = Modifier) {
    val label = stringResource(MR.strings.thread_message_read_status)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(20.dp)
                .clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp)) {
            RaftLucideIcon(
                RaftLucideIconName.CircleCheck,
                Modifier.size(12.dp),
                color = raftArgbHex(RaftColor.ForegroundHint),
            )
        }
        Box(Modifier.padding(start = 4.dp)) {
            Text(label, style = RaftType.Caption, color = RaftColor.ForegroundHint, maxLines = 1)
        }
    }
}
