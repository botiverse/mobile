package build.raft.app.thread

import build.raft.app.data.DataResult
import build.raft.app.data.RaftAgentLounges
import build.raft.app.lookupAgentLounge
import build.raft.app.markdown.raftLoungeConversationAgentName
import build.raft.app.markdown.resolveRaftLoungeRefAgent

/**
 * Before a `lounge:@agent…` ref opens (slock #16279): this client knows that agent's Lounge, so the
 * conversation resolver ([resolveMarkdownLoungeConversation]) finds it. The agent is the one this
 * page's directory names so, by the server's rule ([resolveRaftLoungeRefAgent]). A Lounge this client
 * holds ([RaftAgentLounges]: opened, loaded, or carried on the agent's DM row) is used as it is; any
 * other is looked up read-only (GET /agents/:id/lounge, slock #16297), never created: a ref names only
 * an existing Lounge, so an agent with none (404 agent_lounge_not_found), an agent the page does not
 * know, or a failed lookup leaves it unknown, and the open that follows fails with the ref's ordinary
 * "unable to open" toast, the failed request's trace id included. Any other [conversationLabel] (a
 * channel, a DM peer): nothing to do.
 */
internal suspend fun ThreadRouteEventHandler.prepareMarkdownRefLounge(conversationLabel: String) {
    val agentName = raftLoungeConversationAgentName(conversationLabel) ?: return
    val serverId = currentServerId?.takeIf { it.isNotBlank() } ?: return
    val agent = resolveRaftLoungeRefAgent(agentName, renderedThread.toMarkdownLoungeRefAgents()) ?: return
    if (RaftAgentLounges.loungeOfAgent(serverId, agent.id) != null) return
    val looked =
        lookupAgentLounge(
            networkClient = sharedStores.networkClient(storeScope),
            serverId = serverId,
            agentId = agent.id,
            agentName = agent.displayName ?: agent.name,
        )
    bridge.log(
        "thread_markdown_lounge_lookup result=${if (looked is DataResult.Content) "found" else "not_found"} agentId=${agent.id}"
    )
}
