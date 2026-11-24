package build.raft.app.data

import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The agent and universal-seat part of `GET /billing/subscription` (web `serverStore.loadBilling`,
 * read by `CreateAgentDialog`): the plan, server-resolved [pricing], `capacity.maxAgents` /
 * `capacity.maxUniversalSeats`, and `usage.humans` / `usage.agents` / `usage.universalSeats`.
 * Owners and admins can read it; anyone else gets a 403 and defers cap enforcement to the server
 * (slock #16525, mobile #5588).
 */
internal data class CreateAgentBilling(
    val plan: String? = null,
    /** Server-resolved Pro pricing (slock #16345); null when an older server omits it (`seat_v1`). */
    val pricing: ProPricing? = null,
    /** -1 is no dedicated agent cap; null when the response has no `capacity`. */
    val maxAgents: Int? = null,
    /** -1 is no universal seat cap; null when the response has no `capacity`. */
    val maxUniversalSeats: Int? = null,
    /** Null when the response has no `usage`. */
    val humans: Int? = null,
    /** Null when the response has no `usage`. */
    val agents: Int? = null,
    /** Null when the response has no `usage`. */
    val universalSeats: Double? = null,
)

/**
 * Universal-seat state for creating one more agent on a Pro server (web `agentCreateSeats.ts`,
 * slock #16345, #16367). Present ONLY when the server has universal seats (`maxUniversalSeats != -1`)
 * AND agents take seats under the server's pricing (`seat_v1`). Under `person_v2`, an agent takes
 * no seat, so this is null: no seat line, never short, Create stays enabled.
 */
internal data class CreateAgentSeatState(
    val pricing: ProPricing,
    val seatCapacity: Int,
    /** Tenths of a seat currently in use. */
    val usedTenths: Long,
    /** Seats one more agent would be short of (0 when it fits). */
    val seatsShort: Int,
    /** True when one more agent would exceed [seatCapacity]. */
    val reached: Boolean,
)

/** The create dialog's agent limit (web `getBillingCapacityLimitState(…, "agent", pricing)` + `agentCreateSeats`). */
internal data class CreateAgentLimit(
    val reached: Boolean,
    val usage: Int,
    /** Dedicated agent cap (`-1` is no dedicated cap). */
    val limit: Int,
    /** The plan's display name for "… on Free plan" (web `PLAN_CONFIG[plan].displayName`). */
    val planLabel: String,
    /** Server-resolved Pro pricing used to evaluate seat limits (`seat_v1` when omitted). */
    val pricing: ProPricing = ProPricing.SeatV1,
    /** Universal-seat state when agents consume seats (`seat_v1`); null under `person_v2` or non-seat plans. */
    val seatState: CreateAgentSeatState? = null,
)

@Serializable
private data class WireCreateAgentBilling(
    val plan: String? = null,
    val pricing: String? = null,
    val capacity: WireCreateAgentBillingCapacity? = null,
    val usage: WireCreateAgentBillingUsage? = null,
)

@Serializable
private data class WireCreateAgentBillingCapacity(
    val maxAgents: Int = -1,
    val maxUniversalSeats: Int? = null,
)

@Serializable
private data class WireCreateAgentBillingUsage(
    val humans: Int? = null,
    val agents: Int = 0,
    val universalSeats: Double? = null,
)

private val createAgentBillingJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

internal fun parseCreateAgentBilling(bodyText: String): CreateAgentBilling? =
    runCatching {
        val wire = createAgentBillingJson.decodeFromString(WireCreateAgentBilling.serializer(), bodyText)
        CreateAgentBilling(
            plan = wire.plan?.trim()?.takeIf { it.isNotEmpty() },
            pricing = ProPricing.fromWireOrNull(wire.pricing),
            maxAgents = wire.capacity?.maxAgents,
            maxUniversalSeats = wire.capacity?.maxUniversalSeats,
            humans = wire.usage?.humans,
            agents = wire.usage?.agents,
            universalSeats = wire.usage?.universalSeats,
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
 * Web `CreateAgentDialog` + `agentCreateSeats.ts` (slock #16345, #16367, #16525; mobile #5588):
 * - The plan is billing's, else the server's, else Free.
 * - Dedicated agent cap (`capacity.maxAgents`) applies only when readable from billing (Free/AppSumo);
 *   when billing is unreadable (`null`, e.g. 403 for non-admin Pro members), no client-side cap is
 *   imposed (`-1`) so the server enforces any real cap.
 * - Universal seat cap (`capacity.maxUniversalSeats != -1`) counts under `billingPricing(billing.pricing)`:
 *   under `person_v2` an agent takes 0 tenths (never refused by seats, `seatState = null`);
 *   under `seat_v1` (including older servers omitting `pricing`) an agent takes 1 tenth and is
 *   refused when `usedTenths + 1 > maxUniversalSeats * 10`.
 */
internal fun createAgentLimit(billing: CreateAgentBilling?, serverPlan: String?, serverAgentCount: Int): CreateAgentLimit {
    val plan = billing?.plan ?: serverPlan?.trim()?.takeIf { it.isNotEmpty() } ?: "free"
    val pricing = billingPricing(billing?.pricing)
    val limit = billing?.maxAgents ?: -1
    val usage = (billing?.agents ?: serverAgentCount).coerceAtLeast(0)
    val agentCapReached = limit != -1 && usage + 1 > limit
    val seatCapacity =
        billing?.maxUniversalSeats?.takeIf { it != -1 && pricing.agentsTakeSeats }
    val seatState =
        seatCapacity?.let { capacity ->
            val usedTenths =
                when {
                    billing.humans != null -> seatTenths(billing.humans, billing.agents ?: usage, pricing)
                    billing.universalSeats != null -> (billing.universalSeats * PRO_AGENT_SEAT_BLOCK_SIZE).roundToLong().coerceAtLeast(0L)
                    else -> seatTenths(0, usage, pricing)
                }
            val addedTenths = pricing.rules.agentSeatTenths.toLong()
            val short = seatsShortFor(seats = capacity, usedTenths = usedTenths, needTenths = addedTenths)
            val seatReached = usedTenths + addedTenths > capacity.toLong() * PRO_AGENT_SEAT_BLOCK_SIZE
            CreateAgentSeatState(
                pricing = pricing,
                seatCapacity = capacity,
                usedTenths = usedTenths,
                seatsShort = short,
                reached = seatReached,
            )
        }
    return CreateAgentLimit(
        reached = agentCapReached || (seatState?.reached == true),
        usage = usage,
        limit = limit,
        planLabel = planDisplayName(plan),
        pricing = pricing,
        seatState = seatState,
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
