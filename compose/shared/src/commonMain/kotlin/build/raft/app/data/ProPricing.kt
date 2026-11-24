package build.raft.app.data

import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong
import build.raft.app.model.ServerId
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Which Pro pricing applies (botiverse/slock #16345, #16367, #16449, #16453):
 * - [SeatV1]: 1 seat per person + 0.1 seat per agent, $10/seat/mo ($8.80/mo billed yearly, min 2 seats).
 * - [PersonV2]: 1 seat per person only, agents take no seat, $20/person/mo ($17.60/mo billed yearly, min 1 seat).
 *
 * Only the server resolves a Server's pricing: clients read `pricing` from `GET /billing/subscription`,
 * `GET /billing/pricing`, capacity refusals (`capacityLimit.pricing`), and `GET /actions/:id/seat-request`.
 * Older servers that leave `pricing` out (or have no `/billing/pricing` route) mean [SeatV1].
 * Every seat and price helper requires [ProPricing] explicitly so no caller falls back by omission.
 */
enum class ProPricing(val wireValue: String) {
    SeatV1("seat_v1"),
    PersonV2("person_v2"),
    ;

    val rules: ProPricingRules
        get() =
            when (this) {
                SeatV1 -> SeatV1Rules
                PersonV2 -> PersonV2Rules
            }

    /** Whether an agent takes part of a seat under this pricing ([SeatV1]); under [PersonV2] it takes none. */
    val agentsTakeSeats: Boolean
        get() = rules.agentSeatTenths > 0

    companion object {
        fun fromWireOrNull(raw: String?): ProPricing? =
            when (raw?.trim()) {
                SeatV1.wireValue -> SeatV1
                PersonV2.wireValue -> PersonV2
                else -> null
            }
    }
}

/** Shared `PRO_AGENT_SEAT_BLOCK_SIZE`: ten agents fill one seat under `seat_v1` (tenths arithmetic). */
const val PRO_AGENT_SEAT_BLOCK_SIZE: Int = 10

/** Shared `PRO_PRICING_RULES` (`packages/shared/src/index.ts`, slock #16345). */
data class ProPricingRules(
    /** Tenths of a seat one agent takes: 1 (a tenth) under `seat_v1`, or 0 (none) under `person_v2`. */
    val agentSeatTenths: Int,
    val monthlyUsd: Double,
    /** Annual price per seat, per month. */
    val annualMonthlyUsd: Double,
    val annualUsd: Double,
    val annualDiscountPercent: Int,
    /** Fewest seats a new subscription buys on the web (the server floors at 1). */
    val minCheckoutSeats: Int,
)

private val SeatV1Rules =
    ProPricingRules(
        agentSeatTenths = 1,
        monthlyUsd = 10.0,
        annualMonthlyUsd = 8.8,
        annualUsd = 105.6,
        annualDiscountPercent = 12,
        minCheckoutSeats = 2,
    )

private val PersonV2Rules =
    ProPricingRules(
        agentSeatTenths = 0,
        monthlyUsd = 20.0,
        annualMonthlyUsd = 17.6,
        annualUsd = 211.2,
        annualDiscountPercent = 12,
        minCheckoutSeats = 1,
    )

/**
 * The pricing to count seats and prices under: the server's, never decided on the client.
 * A server that doesn't send `pricing` (older server) or an unreadable billing falls back to [ProPricing.SeatV1].
 */
fun billingPricing(pricing: ProPricing?): ProPricing = pricing ?: ProPricing.SeatV1

/**
 * Shared `seatTenths`: seats in tenths as an integer (a person is 10; an agent is 1 under `seat_v1`,
 * 0 under `person_v2`), avoiding floating-point drift (e.g. `0.1 * 19`).
 */
fun seatTenths(humans: Int, agents: Int, pricing: ProPricing): Long =
    max(0, humans).toLong() * PRO_AGENT_SEAT_BLOCK_SIZE +
        max(0, agents).toLong() * pricing.rules.agentSeatTenths

/** Tenths back to seats (`29 -> 2.9`). */
fun seatsFromTenths(tenths: Long): Double = tenths / PRO_AGENT_SEAT_BLOCK_SIZE.toDouble()

/** Seats counted in tenths, formatted with at most one decimal (`15 -> "1.5"`, `40 -> "4"`). */
fun formatSeatTenths(tenths: Long): String {
    val whole = tenths / PRO_AGENT_SEAT_BLOCK_SIZE
    val fraction = abs(tenths % PRO_AGENT_SEAT_BLOCK_SIZE)
    return if (fraction == 0L) whole.toString() else "$whole.$fraction"
}

/** Shared `calculateProPackQuantity`: seats needed to cover [provisionedHumans] and [provisionedAgents], floor 1. */
fun calculateProPackQuantity(provisionedHumans: Int, provisionedAgents: Int, pricing: ProPricing): Int =
    max(1, ceil(seatTenths(provisionedHumans, provisionedAgents, pricing).toDouble() / PRO_AGENT_SEAT_BLOCK_SIZE).toInt())

/**
 * Shared `seatsShortFor`: seats short when adding [needTenths] to [usedTenths] on a server with [seats] bought.
 * Unlimited capacity (`seats == -1`) or a zero need (`needTenths <= 0`, slock #16453) is never short.
 */
fun seatsShortFor(seats: Int, usedTenths: Long, needTenths: Long): Int {
    if (seats == -1 || needTenths <= 0L) return 0
    return max(0, ceil((usedTenths + needTenths).toDouble() / PRO_AGENT_SEAT_BLOCK_SIZE).toInt() - seats)
}

/** Shared `calculateProPrice` result, rounded to exact cents (`3 * 17.6 = 52.8`, not `52.800000000000004`). */
data class ProPriceSummary(
    val annual: Boolean,
    val seatQuantity: Int,
    val monthlyUsd: Double,
    val annualUsd: Double?,
    val discountPercent: Int,
)

private fun roundUsd(value: Double): Double = (value * 100.0).roundToLong() / 100.0

fun calculateProPrice(seatQuantity: Int, annual: Boolean, pricing: ProPricing): ProPriceSummary {
    val rules = pricing.rules
    val seats = max(1, seatQuantity)
    val unitMonthlyUsd = if (annual) rules.annualMonthlyUsd else rules.monthlyUsd
    val monthlyUsd = roundUsd(seats * unitMonthlyUsd)
    return ProPriceSummary(
        annual = annual,
        seatQuantity = seats,
        monthlyUsd = monthlyUsd,
        annualUsd = if (annual) roundUsd(seats * rules.annualUsd) else null,
        discountPercent = if (annual) rules.annualDiscountPercent else 0,
    )
}

/** Web `ProOffer.formatUsd`: whole dollars without decimals (`"20"`), fractional dollars with two (`"17.60"`). */
fun formatProUsd(value: Double): String {
    val cents = (value * 100.0).roundToLong()
    val whole = cents / 100L
    val rem = abs(cents % 100L)
    return if (rem == 0L) whole.toString() else "$whole.${rem.toString().padStart(2, '0')}"
}

/** Web `monthlySeatUsd`: `"$10"` / `"$8.80"` under `seat_v1`, `"$20"` / `"$17.60"` under `person_v2`. */
fun formatProSeatMonthlyUsd(annual: Boolean, pricing: ProPricing): String {
    val rules = pricing.rules
    val raw =
        if (annual) {
            val cents = (rules.annualMonthlyUsd * 100.0).roundToLong()
            val whole = cents / 100L
            val rem = abs(cents % 100L)
            "$whole.${rem.toString().padStart(2, '0')}"
        } else {
            formatProUsd(rules.monthlyUsd)
        }
    return "$$raw"
}

/**
 * Shared `CapacityLimitDetail` from a 403 refusal (`AGENT_LIMIT_REACHED` / `HUMAN_SEAT_LIMIT_REACHED`).
 * Older servers omit `pricing`, which resolves to [ProPricing.SeatV1].
 */
data class CapacityLimitDetail(
    val limitType: String,
    val limit: Int,
    val usage: Double,
    val nextUsage: Double,
    val humans: Int,
    val agents: Int,
    val pricing: ProPricing? = null,
) {
    val resolvedPricing: ProPricing get() = billingPricing(pricing)
}

@Serializable
private data class WireCapacityLimitEnvelope(
    val capacityLimit: WireCapacityLimitDetail? = null,
)

@Serializable
private data class WireCapacityLimitDetail(
    val limitType: String = "",
    val limit: Int = 0,
    val usage: Double = 0.0,
    val nextUsage: Double = 0.0,
    val humans: Int = 0,
    val agents: Int = 0,
    val pricing: String? = null,
)

private val proPricingJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

fun parseCapacityLimitDetail(bodyText: String?): CapacityLimitDetail? {
    val text = bodyText?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching {
        val wire = proPricingJson.decodeFromString(WireCapacityLimitEnvelope.serializer(), text).capacityLimit ?: return null
        if (wire.limitType.isBlank()) return null
        CapacityLimitDetail(
            limitType = wire.limitType,
            limit = wire.limit,
            usage = wire.usage,
            nextUsage = wire.nextUsage,
            humans = wire.humans,
            agents = wire.agents,
            pricing = ProPricing.fromWireOrNull(wire.pricing),
        )
    }.getOrNull()
}

/**
 * Seats short for one more agent given a universal seat refusal (`refusedAgentSeatsShort`, slock #16345).
 * Under `person_v2` an agent takes 0 tenths, so it is 0 seats short.
 */
fun refusedAgentSeatsShort(detail: CapacityLimitDetail): Int {
    val pricing = detail.resolvedPricing
    val needTenths = pricing.rules.agentSeatTenths.toLong()
    return seatsShortFor(
        seats = detail.limit,
        usedTenths = seatTenths(detail.humans, detail.agents, pricing),
        needTenths = needTenths,
    )
}

/**
 * What a `seats:add` action card is asking for (shared `SeatsAddPurpose`, slock #16449).
 */
@Serializable
sealed interface SeatsAddPurpose {
    @Serializable
    data class AgentCreate(val name: String) : SeatsAddPurpose

    @Serializable
    data object AssistantCreate : SeatsAddPurpose

    @Serializable
    data class Invite(val emails: List<String>) : SeatsAddPurpose

    @Serializable
    data class MemberPromote(val userId: String, val name: String) : SeatsAddPurpose

    @Serializable
    data class Join(val userId: String, val name: String) : SeatsAddPurpose
}

/** Parses wire `action.purpose` of a `seats:add` card leniently; unknown or malformed returns null. */
internal fun parseSeatsAddPurpose(raw: JsonObject?): SeatsAddPurpose? {
    if (raw == null) return null
    val kind = (raw["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return null
    fun str(key: String): String? = (raw[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    return when (kind) {
        "agent:create" -> str("name")?.let { SeatsAddPurpose.AgentCreate(it) }
        "assistant:create" -> SeatsAddPurpose.AssistantCreate
        "invite" -> {
            val emails =
                (raw["emails"] as? JsonArray).orEmpty().mapNotNull { el ->
                    (el as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
                }
            if (emails.isNotEmpty()) SeatsAddPurpose.Invite(emails) else null
        }
        "member:promote" -> {
            val userId = str("userId") ?: return null
            val name = str("name") ?: return null
            SeatsAddPurpose.MemberPromote(userId = userId, name = name)
        }
        "join" -> {
            val userId = str("userId") ?: return null
            val name = str("name") ?: return null
            SeatsAddPurpose.Join(userId = userId, name = name)
        }
        else -> null
    }
}

/**
 * Shared `seatsAddPurposeTenths` (slock #16449): tenths of a seat a seat request takes under [pricing].
 * A person takes 10; an agent or assistant takes 1 under `seat_v1` and 0 under `person_v2`.
 */
fun seatsAddPurposeTenths(purpose: SeatsAddPurpose, pricing: ProPricing): Long =
    when (purpose) {
        is SeatsAddPurpose.AgentCreate,
        SeatsAddPurpose.AssistantCreate -> pricing.rules.agentSeatTenths.toLong()
        is SeatsAddPurpose.Invite ->
            purpose.emails.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet().size.toLong() * PRO_AGENT_SEAT_BLOCK_SIZE
        is SeatsAddPurpose.MemberPromote,
        is SeatsAddPurpose.Join -> PRO_AGENT_SEAT_BLOCK_SIZE.toLong()
    }

/**
 * Answer from `GET /api/actions/:messageId/seat-request` (slock #16449, #16453).
 * [pricing] is the card's server's pricing (absent from older servers -> [ProPricing.SeatV1]).
 */
data class SeatRequestCheck(
    val requesterAllowed: Boolean,
    val seatsShort: Int,
    val pricing: ProPricing = ProPricing.SeatV1,
    /** True when the server explicitly returned a recognised `pricing` value. */
    val pricingExplicit: Boolean = false,
)

/**
 * Effective seats short for a seat-request card (slock #16449 + #16453):
 * - When the requester can no longer perform the action: 0 (completes without charge).
 * - When the purpose takes 0 tenths under [SeatRequestCheck.pricing] (agent/assistant under `person_v2`):
 *   0 — what takes nothing is never short, even on a server with more people than seats.
 * - Otherwise `check.seatsShort.coerceAtLeast(0)`.
 */
fun seatRequestEffectiveSeatsShort(purpose: SeatsAddPurpose?, check: SeatRequestCheck): Int {
    if (!check.requesterAllowed) return 0
    if (purpose != null && seatsAddPurposeTenths(purpose, check.pricing) == 0L) return 0
    return max(0, check.seatsShort)
}

@Serializable
private data class WireBillingPricingResponse(val pricing: String? = null)

internal fun parseBillingPricingResponse(bodyText: String?): ProPricing? {
    val text = bodyText?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching {
        ProPricing.fromWireOrNull(proPricingJson.decodeFromString(WireBillingPricingResponse.serializer(), text).pricing)
    }.getOrNull()
}

/** Web `useServerProPricing` retry backoff (`PRICING_RETRY_MS`, slock #16367). */
internal const val PRO_PRICING_RETRY_MS: Long = 1_500L

/**
 * Resolves a server's Pro pricing (web `useServerProPricing`, slock #16367):
 * - When billing is already loaded ([knownBillingPresent]), returns `billingPricing(knownBillingPricing)` without calling `/billing/pricing`.
 * - Otherwise calls `GET /api/billing/pricing` (readable by any member) for [serverId]:
 *   - 200 with recognised `pricing` -> returns it.
 *   - 404 (older server without `/billing/pricing`) -> [ProPricing.SeatV1].
 *   - Any other failure -> retries once after [retryDelayMs]; if still failing, returns null so the UI never quotes a guessed price.
 */
internal suspend fun loadServerProPricing(
    networkClient: RaftNetworkClient,
    serverId: String,
    knownBillingPresent: Boolean = false,
    knownBillingPricing: ProPricing? = null,
    retryDelayMs: Long = PRO_PRICING_RETRY_MS,
): ProPricing? {
    if (knownBillingPresent) return billingPricing(knownBillingPricing)
    val cleanServerId = serverId.trim()
    if (cleanServerId.isEmpty()) return null
    var triesLeft = 1
    while (true) {
        val response =
            try {
                networkClient.request(
                    priority = DataRequestPriority.Visible,
                    path = "billing/pricing",
                    serverIdOverride = ServerId(cleanServerId),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
        if (response != null) {
            if (response.isSuccessful) {
                return parseBillingPricingResponse(response.bodyText)
            }
            if (response.code == 404) {
                return ProPricing.SeatV1
            }
        }
        if (triesLeft <= 0) return null
        triesLeft -= 1
        if (retryDelayMs > 0L) delay(retryDelayMs)
    }
}
