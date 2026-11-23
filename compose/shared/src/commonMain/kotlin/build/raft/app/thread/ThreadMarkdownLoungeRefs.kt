package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.RaftAgentLounges
import build.raft.app.markdown.raftLoungeConversationAgentName
import build.raft.app.markdown.resolveRaftLoungeRefAgent

/**
 * Before a `lounge:@agent…` ref opens (slock #16279): this client knows that agent's Lounge, so the
 * conversation resolver ([resolveMarkdownLoungeConversation]) finds it. The agent is the one this
 * page's directory names so, by the server's rule ([resolveRaftLoungeRefAgent]). A Lounge this client
 * holds ([RaftAgentLounges]: opened, loaded, or carried on the agent's DM row) is used as it is; any
 * other is opened the way the agent profile's Lounge button opens it (POST /agents/:id/lounge, web
 * `openLounge`). An agent the page does not know, or a failed open, leaves it unknown: the open that
 * follows fails with the ref's ordinary toast, the failed request's trace id included. Any other
 * [conversationLabel] (a channel, a DM peer): nothing to do.
 */
internal suspend fun ThreadRouteEventHandler.prepareMarkdownRefLounge(conversationLabel: String) {
    val agentName = raftLoungeConversationAgentName(conversationLabel) ?: return
    val serverId = currentServerId?.takeIf { it.isNotBlank() } ?: return
    val agent = resolveRaftLoungeRefAgent(agentName, renderedThread.toMarkdownLoungeRefAgents()) ?: return
    if (RaftAgentLounges.loungeOfAgent(serverId, agent.id) != null) return
    val opened =
        openAgentLounge(
            networkClient = sharedStores.networkClient(storeScope),
            serverId = serverId,
            agentId = agent.id,
            agentName = agent.displayName ?: agent.name,
        )
    bridge.log(
        "thread_markdown_lounge_open result=${if (opened is DataResult.Content) "opened" else "failed"} agentId=${agent.id}"
    )
}
