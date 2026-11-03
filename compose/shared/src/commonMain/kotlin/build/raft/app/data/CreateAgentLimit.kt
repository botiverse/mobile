package build.raft.app.data

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The agent part of `GET /billing/subscription` (web `serverStore.loadBilling`, read by
 * `CreateAgentDialog`): the plan, `capacity.maxAgents` and `usage.agents`. Owners and admins
 * can read it; anyone else gets a 403 and the dialog falls back to the plan's own limits.
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
 * Web `CreateAgentDialog`: the plan is the billing's, else the server's, else Free; the cap is
 * billing `capacity.maxAgents`, else the plan's own `maxAgents` (`getEffectiveLimits`); the
 * usage is billing `usage.agents`, else the server's agents. Reached when one more would pass
 * the cap. External agents count like any other.
 */
internal fun createAgentLimit(billing: CreateAgentBilling?, serverPlan: String?, serverAgentCount: Int): CreateAgentLimit {
    val plan = billing?.plan ?: serverPlan?.trim()?.takeIf { it.isNotEmpty() } ?: "free"
    val limit = billing?.maxAgents ?: planMaxAgents(plan)
    val usage = (billing?.agents ?: serverAgentCount).coerceAtLeast(0)
    return CreateAgentLimit(
        reached = limit != -1 && usage + 1 > limit,
        usage = usage,
        limit = limit,
        planLabel = planDisplayName(plan),
    )
}

/** raft-shared `PLAN_CONFIG[plan].limits.maxAgents` (staging 947bd94): only Pro caps agents, at one pack. */
private fun planMaxAgents(plan: String): Int =
    when (plan) {
        "pro" -> PRO_PACK_AGENT_SEATS
        else -> -1
    }

/** raft-shared `PRO_PACK_AGENT_SEATS` (= `PRO_AGENT_SEAT_BLOCK_SIZE`). */
private const val PRO_PACK_AGENT_SEATS = 10

/** raft-shared `PLAN_CONFIG[plan].displayName`; an unknown plan shows as Free, as the web's fallback. */
private fun planDisplayName(plan: String): String =
    when (plan) {
        "pro" -> "Pro"
        "founder" -> "Founder"
        "partner" -> "Partner"
        else -> "Free"
    }
