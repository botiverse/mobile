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
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageMention
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
    /**
     * Web `buildMentionMap` (ChatPanel `mentionMap`): every mentionable `name` (handle, exact) the
     * page knows -> its identity; agents first, then humans (a human wins a shared name, as on web).
     * Consulted only when the message's own resolved `mentions[]` has no entry for the name.
     */
    val directoryMentions: Map<String, MessageMention> = emptyMap(),
    /** Agents with `managedByAppInstallId` (app-managed bots): no badge, web `isManagedBot`. */
    val managedAgentIds: Set<String> = emptySet(),
)

internal val LocalThreadPeerReadReceipts = compositionLocalOf<ThreadPeerReadReceiptsUi?> { null }

/** A mention token's name: the handle after `@`, matched exactly like web `MentionEntry` keys. */
internal fun threadMentionTokenName(label: String): String = label.trim().removePrefix("@").trim()

/**
 * Web MessageItem: `structuredMentionMap.get(name) ?? mentionMap.get(name)`. The message's resolved
 * `mentions[]` (ids from the server) win; the page directory is the fallback for rows that carry
 * none (e.g. an optimistic or cached row).
 */
internal fun ThreadPeerReadReceiptsUi.resolveMention(message: RaftThreadMessageItem, label: String): MessageMention? {
    val name = threadMentionTokenName(label)
    if (name.isEmpty()) return null
    return message.mentions.lastOrNull { it.name.trim() == name } ?: directoryMentions[name]
}

/** Web `agentReadBadgesEnabled`: only the human sender of the message sees its badges. */
private fun RaftThreadMessageItem.showsPeerReadReceipts(): Boolean =
    isOwn && authorKind != "agent" && (seq ?: 0L) > 0L

/** The @mention badge states for [message]'s body, or null when it gets none. */
internal fun ThreadPeerReadReceiptsUi.mentionReadStates(message: RaftThreadMessageItem): RaftMarkdownMentionReadStates? {
    if (!message.showsPeerReadReceipts() || scope !is PeerReadReceiptScope.Peers) return null
    val seq = message.seq
    return RaftMarkdownMentionReadStates { label ->
        val mention = resolveMention(message, label) ?: return@RaftMarkdownMentionReadStates null
        // Only agent mentions carry a badge; app-managed bots have no read semantics (yezizp 2026-10-21).
        if (mention.type != "agent" || mention.id in managedAgentIds) return@RaftMarkdownMentionReadStates null
        projectAgentReadReceipt(scope, mention.id, seq)
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

/**
 * Web `buildMentionMap(agents, members, channelAgents, channelHumans)`: name -> identity, agents
 * (server directory, then channel members) before humans so a human wins a shared name.
 */
internal fun threadMentionDirectory(
    serverMembers: List<MemberProfile>,
    channelMembers: List<RaftThreadMemberItem>,
): Map<String, MessageMention> {
    fun handleOf(value: String) = value.trim().removePrefix("@").trim()
    val map = linkedMapOf<String, MessageMention>()
    fun put(type: String, id: String, handle: String) {
        val name = handleOf(handle)
        if (name.isNotEmpty() && id.isNotBlank()) map[name] = MessageMention(type = type, id = id, name = name)
    }
    serverMembers.filter { it.kind == MemberKind.Agent }.forEach { put("agent", it.id.value, it.handle) }
    channelMembers.filter { it.kind == "agent" }.forEach { put("agent", it.id, it.handle) }
    serverMembers.filter { it.kind == MemberKind.Human }.forEach { put("user", it.id.value, it.handle) }
    channelMembers.filter { it.kind == "human" }.forEach { put("user", it.id, it.handle) }
    return map
}

/** `managedByAppInstallId` agents of the server directory (`GET /agents`). */
internal fun threadManagedAgentIds(serverMembers: List<MemberProfile>): Set<String> =
    serverMembers
        .filter { it.kind == MemberKind.Agent && !it.managedByAppInstallId.isNullOrBlank() }
        .mapTo(mutableSetOf()) { it.id.value }

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
    serverMembersStore: SharedServerMembersStore?,
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
    val membersState = serverMembersStore?.state?.collectAsState()?.value
    val serverMembers =
        remember(membersState, owner) {
            owner?.let { serverMembersStore?.currentServerMembers(ServerId(it.serverId)) }.orEmpty()
        }
    val directory = remember(serverMembers, payload.members, payload.memberCandidates) {
        threadMentionDirectory(serverMembers, payload.members + payload.memberCandidates)
    }
    val managedAgentIds = remember(serverMembers) { threadManagedAgentIds(serverMembers) }
    return remember(scope, dmSurface, directory, managedAgentIds) {
        scope?.let {
            ThreadPeerReadReceiptsUi(
                scope = it,
                dmSurface = dmSurface,
                directoryMentions = directory,
                managedAgentIds = managedAgentIds,
            )
        }
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
