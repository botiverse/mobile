package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred

/** The slock SHA every agent-controls fixture below was copied from. */
internal const val AGENT_CONTROLS_SLOCK_SHA = "7d349b509d6511eb922f7870099017fdda0d3f61"

internal data class AgentControlsRecordedRequest(
    val priority: DataRequestPriority,
    val method: String,
    val path: String,
    val bodyText: String?,
    val query: List<Pair<String, String>> = emptyList(),
)

/**
 * Answers `METHOD path` from a script, in order; the last scripted answer repeats.
 * Anything unscripted is a 404. A [hold] keeps that route's answer until its gate completes.
 */
internal class ScriptedAgentControlsNetworkClient : RaftNetworkClient {
    private val script = mutableMapOf<String, MutableList<HomeNetworkResponse>>()
    private val served = mutableMapOf<String, Int>()
    private val holds = mutableMapOf<String, CompletableDeferred<Unit>>()
    val requests = mutableListOf<AgentControlsRecordedRequest>()

    /** `METHOD path` answers only once [gate] completes (a request still in flight). */
    fun hold(method: String, path: String, gate: CompletableDeferred<Unit>) {
        holds["$method $path"] = gate
    }

    fun respond(method: String, path: String, code: Int, body: String, headers: Map<String, List<String>> = emptyMap()) {
        val response =
            if (code in 200..299) {
                HomeNetworkResponse(code = code, bodyText = body, responseHeaders = headers)
            } else {
                HomeNetworkResponse(code = code, bodyText = null, errorBodyText = body, responseHeaders = headers)
            }
        script.getOrPut("$method $path") { mutableListOf() }.add(response)
    }

    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?
    ): HomeNetworkResponse {
        requests += AgentControlsRecordedRequest(priority, method, path, bodyText, query)
        val key = "$method $path"
        holds[key]?.await()
        val answers = script[key] ?: return HomeNetworkResponse(code = 404, bodyText = null)
        val index = served[key] ?: 0
        served[key] = index + 1
        return answers[minOf(index, answers.lastIndex)]
    }
}
