package build.raft.app.data

import build.raft.app.RaftJointConnectionsCache
import build.raft.app.ProcessAttachmentAuthScopes
import build.raft.app.SharedThreadPayloadStore
import build.raft.app.network.sync.RaftThreadReplyWindowCache

/**
 * What signing out (the auth-terminal cleanup) does to local data.
 *
 * Owner decision (artin, 2026-10-27, WeChat/QQ style): signing out does NOT delete the account's stored
 * data. Every durable copy below is keyed by the account (principal), and its reads filter by that key,
 * so the next account cannot read it, and signing back in as the same account shows it again, offline
 * too:
 * - offline resources, e.g. the Saved list (environment + account + server rows) — in-flight writes of
 *   the signed-out session are still fenced;
 * - stored messages (`sync-core.messages.v2` partitions: environment + server + principal; never cleared
 *   here) and the reply-preview windows (server + principal page keys);
 * - Home row hints, Activity first pages, workspace notification preferences (user + server keys);
 * - durable member rosters, machines, joint connections (environment + account + server rows);
 * - incremental sync cursors (environment + account + server) and recent searches (server + principal).
 *
 * Losing access to a server still deletes that server's data (`onServerAccessLost`).
 *
 * In-memory state always resets. Durable data that is NOT keyed by account is still cleared:
 * member avatar references are stored per server only, and before the new owner is known they would
 * answer with the previous account's page.
 */
internal class AuthTerminalLocalDataReset(
    private val homeSavedStateCache: SharedHomeSavedStateCache,
    private val offlineResourceCache: RaftOfflineResourceCache,
    private val memberAvatarRefStore: RaftMemberAvatarRefStore,
    private val jointConnectionsCache: () -> RaftJointConnectionsCache,
    private val agentDetailCache: RaftAgentDetailCache,
    private val replyWindowCache: RaftThreadReplyWindowCache?,
    private val threadPayloadStore: SharedThreadPayloadStore,
    /** Session-only viewer caches (app link previews, artifact summaries); only the ones that exist. */
    private val clearViewerMemoryCaches: () -> Unit,
) {
    /** Runs inside the auth terminal-revision gate: synchronous, no suspension. */
    fun run() {
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
}
