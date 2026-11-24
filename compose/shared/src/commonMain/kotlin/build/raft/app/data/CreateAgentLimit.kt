package build.raft.app.data

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The agent part of `GET /billing/subscription` (web `serverStore.loadBilling`, read by
 * `CreateAgentDialog`): the plan, `capacity.maxAgents` and `usage.agents`. Owners and admins
 * can read it; anyone else gets a 403 and defers agent-cap enforcement to the server.
 */
internal data class CreateAgentBilling(
    val plan: String? = null,
    /** -1 is no agent cap; null when the response has no `capacity`. */
    val maxAgents: Int? = null,
    /** Null when the response has no `usage`. */
    val agents: Int? = null,
)

/** The create dialog's agent limit (web `getBillingCapacityLimitState(…, "agent")`). */
internal data class CreateAgentLimit(
    val reached: Boolean,
    val usage: Int,
    /** -1 is no cap. */
    val limit: Int,
    /** The plan's display name for "… on Free plan" (web `PLAN_CONFIG[plan].displayName`). */
    val planLabel: String,
)

@Serializable
private data class WireCreateAgentBilling(
    val plan: String? = null,
    val capacity: WireCreateAgentBillingCapacity? = null,
    val usage: WireCreateAgentBillingUsage? = null,
)

@Serializable
private data class WireCreateAgentBillingCapacity(val maxAgents: Int = -1)

@Serializable
private data class WireCreateAgentBillingUsage(val agents: Int = 0)

private val createAgentBillingJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

internal fun parseCreateAgentBilling(bodyText: String): CreateAgentBilling? =
    runCatching {
        val wire = createAgentBillingJson.decodeFromString(WireCreateAgentBilling.serializer(), bodyText)
        CreateAgentBilling(
            plan = wire.plan?.trim()?.takeIf { it.isNotEmpty() },
            maxAgents = wire.capacity?.maxAgents,
            agents = wire.usage?.agents,
        )
    }.getOrNull()

/** `GET /billing/subscription` for the create dialog; null when it can't be read (403, offline, bad body). */
internal suspend fun loadCreateAgentBilling(networkClient: RaftNetworkClient): CreateAgentBilling? =
    try {
        val response = networkClient.request(priority = DataRequestPriority.Visible, path = "billing/subscription")
        if (response.isSuccessful) response.bodyText?.let(::parseCreateAgentBilling) else null
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

/**
 * The plan is the billing's, else the server's, else Free; the cap comes strictly from billing
 * `capacity.maxAgents` (when readable), otherwise `-1` (let the server enforce any real cap and
 * return its reason so non-admin members on Pro servers with >= 10 agents are never falsely
 * blocked client-side under universal seats or per-person `person_v2` pricing).
 */
internal fun createAgentLimit(billing: CreateAgentBilling?, serverPlan: String?, serverAgentCount: Int): CreateAgentLimit {
    val plan = billing?.plan ?: serverPlan?.trim()?.takeIf { it.isNotEmpty() } ?: "free"
    val limit = billing?.maxAgents ?: -1
    val usage = (billing?.agents ?: serverAgentCount).coerceAtLeast(0)
    return CreateAgentLimit(
        reached = limit != -1 && usage + 1 > limit,
        usage = usage,
        limit = limit,
        planLabel = planDisplayName(plan),
    )
}

/** raft-shared `PLAN_CONFIG[plan].displayName`; an unknown plan shows as Free, as the web's fallback. */
private fun planDisplayName(plan: String): String =
    when (plan) {
        "pro" -> "Pro"
        "founder" -> "Founder"
        "partner" -> "Partner"
        else -> "Free"
    }
