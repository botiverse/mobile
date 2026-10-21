package build.raft.app.data

import build.raft.app.model.ServerId

/** The slock SHA every agent-controls fixture below was copied from. */
internal const val AGENT_CONTROLS_SLOCK_SHA = "7d349b509d6511eb922f7870099017fdda0d3f61"

internal data class AgentControlsRecordedRequest(
    val priority: DataRequestPriority,
    val method: String,
    val path: String,
    val bodyText: String?
)

/** Answers `METHOD path` from a script; anything unscripted is a 404. */
internal class ScriptedAgentControlsNetworkClient(
    private val script: MutableMap<String, ArrayDeque<HomeNetworkResponse>> = mutableMapOf()
) : RaftNetworkClient {
    val requests = mutableListOf<AgentControlsRecordedRequest>()

    fun respond(method: String, path: String, code: Int, body: String) {
        val response =
            if (code in 200..299) {
                HomeNetworkResponse(code = code, bodyText = body)
            } else {
                HomeNetworkResponse(code = code, bodyText = null, errorBodyText = body)
            }
        script.getOrPut("$method $path") { ArrayDeque() }.addLast(response)
    }

    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?
    ): HomeNetworkResponse {
        requests += AgentControlsRecordedRequest(priority, method, path, bodyText)
        val queue = script["$method $path"] ?: return HomeNetworkResponse(code = 404, bodyText = null)
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }
}
