package build.raft.app.data

import build.raft.app.ProcessAttachmentAuthScopes
import build.raft.app.RaftJointConnectionsCache
import build.raft.app.SharedThreadPayloadStore
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.RaftThreadReplyWindowCache
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID

/**
 * The lifetime of an account's local data: what signing out ([onSignOut]) and losing access to a server
 * ([onServerAccessLost]) do to it.
 *
 * Owner decision (artin, 2026-10-27, WeChat/QQ style): signing out does NOT delete the account's stored
 * data. Every durable copy below is keyed by the account (principal), and its reads filter by that key,
 * so the next account cannot read it, and signing back in as the same account shows it again, offline
 * too:
 * - offline resources, e.g. the Saved list (environment + account + server rows); in-flight writes of
 *   the signed-out session are still fenced;
 * - stored messages (`sync-core.messages.v2` partitions: environment + server + principal) and the
 *   reply-preview windows (server + principal page keys);
 * - Home row hints, Activity first pages, workspace notification preferences (user + server keys);
 * - durable member rosters, machines, joint connections (environment + account + server rows);
 * - incremental sync cursors (environment + account + server) and recent searches (server + principal).
 *
 * Losing access to a server deletes that server's data: the stores here drop it in [onServerAccessLost],
 * the rest (members, identities, machines, joint connections, agent details, sync cursors, searches) in
 * the provider's `onServerAccessLost`.
 *
 * In-memory state always resets on sign-out. Durable data that is NOT keyed by account is still cleared:
 * member avatar references are stored per server only, and before the new owner is known they would
 * answer with the previous account's page.
 */
internal class AccountLocalData(
    private val homeSavedStateCache: SharedHomeSavedStateCache,
    private val offlineResourceCache: RaftOfflineResourceCache,
    private val homeRowHints: RaftLastCountsPersistStorage,
    private val activityFirstPages: RaftLastCountsPersistStorage,
    private val notificationPreferences: SettingsServerNotificationLastKnownStore,
    private val messageEngine: CanonicalMessageV2GenerationEngine,
    private val memberAvatarRefStore: RaftMemberAvatarRefStore,
    private val jointConnectionsCache: () -> RaftJointConnectionsCache,
    private val agentDetailCache: RaftAgentDetailCache,
    private val replyWindowCache: RaftThreadReplyWindowCache?,
    private val threadPayloadStore: SharedThreadPayloadStore,
    /** Session-only viewer caches (app link previews, artifact summaries); only the ones that exist. */
    private val clearViewerMemoryCaches: () -> Unit,
    private val logger: (String) -> Unit = {},
) {
    /** Sign-out. Runs inside the auth terminal-revision gate: synchronous, no suspension. */
    fun onSignOut() {
        ProcessAttachmentAuthScopes.retireAccount()
        homeSavedStateCache.clear()
        offlineResourceCache.fenceInFlightWrites()
        memberAvatarRefStore.clearAll()
        jointConnectionsCache().clearMemory()
        agentDetailCache.clearMemory()
        replyWindowCache?.clearMemory()
        threadPayloadStore.clearAll()
        clearViewerMemoryCaches()
    }

    /**
     * Removed from (or deleted) server [serverId]: that server's copies of [accountId] (null = every
     * account) go, on disk too; the account's other servers keep theirs. [messagePartition] is the
     * server's `sync-core.messages.v2` partition under the lost grant.
     */
    suspend fun onServerAccessLost(serverId: String, accountId: String?, messagePartition: DurablePartitionKey) {
        offlineResourceCache.wipeServer(serverId, accountId)
        homeRowHints.dropServer(serverId, accountId)
        activityFirstPages.dropServer(serverId, accountId)
        notificationPreferences.dropServer(serverId, accountId)
        replyWindowCache?.dropServer(serverId, accountId)
        if (messagePartition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID) {
            messageEngine.clearPartition(messagePartition)
            logger("durable_access_evict scope=server server=${messagePartition.serverId}")
        }
    }
}
