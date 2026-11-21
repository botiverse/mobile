package build.raft.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * How the server applies a saved runtime config change: `configApply` on the `PATCH /agents/:id`
 * answer (botiverse/slock `packages/server/src/routes/agents.ts`, reconciler step 11b + #15669 /
 * #15714; web `AgentConfigApply` in `packages/web/src/store/agentStore.ts`).
 *
 * - [Applied] `applied`: applied immediately on an idle capable daemon.
 * - [Restarted] `restarted`: restarted immediately (busy on `agent:config-interrupt-v1` daemon,
 *   idle older daemon, or explicit `restartMode`).
 * - [WhenIdle] `when_idle`: older daemon without `agent:config-interrupt-v1` while busy; switches
 *   automatically after the current turn.
 * - [Saved] `saved`: only saved; the next start uses it (a stopped agent or offline Computer that
 *   re-syncs automatically on reconnect).
 */
enum class AgentConfigApply(val wireValue: String) {
    Applied("applied"),
    WhenIdle("when_idle"),
    Restarted("restarted"),
    Saved("saved");

    companion object {
        fun fromWire(value: String?): AgentConfigApply? = entries.firstOrNull { it.wireValue == value }
    }
}

@Serializable
private data class AgentConfigApplyWire(
    val configApply: String? = null,
    val configApplyNewSession: Boolean? = null
)

private val agentConfigApplyJson = Json { ignoreUnknownKeys = true }

/**
 * The `configApply` of a `PATCH /agents/:id` answer; null when absent (an older server) or not
 * one of the known values.
 */
fun parseSharedAgentConfigApply(bodyText: String): AgentConfigApply? =
    runCatching { agentConfigApplyJson.decodeFromString(AgentConfigApplyWire.serializer(), bodyText) }
        .getOrNull()
        ?.configApply
        ?.let(AgentConfigApply::fromWire)

/**
 * The `configApplyNewSession` flag of a `PATCH /agents/:id` answer (botiverse/slock #15669):
 * whether the server decided the saved change starts a new session; null when absent.
 */
fun parseSharedAgentConfigApplyNewSession(bodyText: String): Boolean? =
    runCatching { agentConfigApplyJson.decodeFromString(AgentConfigApplyWire.serializer(), bodyText) }
        .getOrNull()
        ?.configApplyNewSession
