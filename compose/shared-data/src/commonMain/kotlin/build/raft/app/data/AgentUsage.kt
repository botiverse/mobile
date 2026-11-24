package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.model.MillisPerDay
import build.raft.app.model.civilDateFromEpochDay
import build.raft.app.model.floorDiv
import build.raft.app.model.floorMod
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.model.raftEpochDayForDate
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Agent token usage wire contract and pure aggregation math.
 *
 * Mirrors `botiverse/slock`:
 * - `packages/shared/src/agentUsage.ts` (`GET /api/servers/:serverId/agent-usage`)
 * - `packages/web/src/features/agent/agentUsage.ts` (request window, null-vs-0 semantics,
 *   per-day summaries, like-for-like prior-period / same-weekday comparison, unreported reasons).
 *
 * Counts are nullable `Long?`: `null` means unreported (shown as "—", omitted from sums),
 * `0` is a real zero, and `reasoningOutput` is a subset of `output` (never added to it).
 */
@RaftCoreInternalApi const val AGENT_USAGE_MAX_RANGE_DAYS: Int = 90

@RaftCoreInternalApi enum class AgentUsageRange(val days: Int) {
    Days7(7),
    Days14(14),
    Days30(30),
}

/** What the Usage tab shows: a range of days ending today and, optionally, one picked day in it. */
@RaftCoreInternalApi data class AgentUsageView(
    val range: AgentUsageRange = AgentUsageRange.Days7,
    val day: String? = null,
)

@RaftCoreInternalApi data class AgentUsageDateWindow(
    val from: String,
    val to: String,
)

/**
 * Wire state of `agents[].computer_reports_usage`:
 * - [True]: Computer is new enough (>= 1.25.0) to report usage.
 * - [False]: Computer is too old / version unknown.
 * - [Null]: explicit JSON `null` — agent has no Computer (hosted, external, or detached).
 * - [Unknown]: field omitted (older server) or unexpected wire type.
 */
@RaftCoreInternalApi enum class ComputerReportsUsageWire {
    True,
    False,
    Null,
    Unknown,
}

@RaftCoreInternalApi data class AgentUsageRow(
    val day: String,
    val agentId: String,
    val runtime: String?,
    val model: String,
    val inputNew: Long?,
    val cacheRead: Long?,
    val cacheWrite: Long?,
    val output: Long?,
    val reasoningOutput: Long?,
    val turns: Long,
)

@RaftCoreInternalApi data class AgentUsageContextEntry(
    val agentId: String,
    val contextUsed: Long?,
    val contextWindow: Long?,
    val at: String,
)

@RaftCoreInternalApi data class AgentUsageAgentEntry(
    val agentId: String,
    val since: String?,
    val usageReported: Boolean,
    val computerReportsUsage: ComputerReportsUsageWire = ComputerReportsUsageWire.Unknown,
    val runtimeReportsUsage: Boolean? = null,
)

@RaftCoreInternalApi data class AgentUsageResponse(
    val from: String,
    val to: String,
    val asOf: String,
    val since: String?,
    val visibleAgents: Int,
    val agents: List<AgentUsageAgentEntry>,
    val rows: List<AgentUsageRow>,
    val context: List<AgentUsageContextEntry>,
)

@RaftCoreInternalApi data class AgentUsageSums(
    val inputNew: Long? = null,
    val cacheRead: Long? = null,
    val cacheWrite: Long? = null,
    val output: Long? = null,
    val reasoning: Long? = null,
    val turns: Long = 0L,
)

@RaftCoreInternalApi data class AgentUsageDay(
    val day: String,
    val noData: Boolean,
    val partial: Boolean,
    val sums: AgentUsageSums,
)

@RaftCoreInternalApi data class AgentUsageModel(
    val runtime: String?,
    val model: String,
    val tokens: Long?,
)

@RaftCoreInternalApi data class AgentUsageContextReading(
    val used: Long,
    val window: Long,
)

@RaftCoreInternalApi data class AgentUsageFigures(
    val days: List<AgentUsageDay>,
    val since: String?,
    val asOf: String,
    val noData: Boolean,
    val sums: AgentUsageSums,
    val models: List<AgentUsageModel>,
    val compared: Long?,
    val base: Long?,
    val baseDay: String?,
)

@RaftCoreInternalApi enum class UsageUnreportedReason(val testTag: String, val offersComputer: Boolean) {
    RuntimeNoUsage("agent-usage-runtime-no-usage", offersComputer = false),
    ComputerTooOld("agent-usage-unsupported", offersComputer = true),
    NotRun("agent-usage-not-run", offersComputer = false),
    NoComputer("agent-usage-no-computer", offersComputer = false),
    Unknown("agent-usage-unsupported", offersComputer = true),
}

@RaftCoreInternalApi sealed interface AgentUsageSummary {
    data class Unsupported(val reason: UsageUnreportedReason) : AgentUsageSummary
    data object Empty : AgentUsageSummary
    data class Ready(
        val figures: AgentUsageFigures,
        val context: AgentUsageContextReading?,
    ) : AgentUsageSummary
}

private val AgentUsageUtcDayRegex = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")

@RaftCoreInternalApi fun agentUsageFormatUtcEpochDay(epochDay: Long): String {
    val date = civilDateFromEpochDay(epochDay)
    val year = date.year.toString().padStart(4, '0')
    val month = date.month.toString().padStart(2, '0')
    val day = date.day.toString().padStart(2, '0')
    return "$year-$month-$day"
}

/** The UTC date (`YYYY-MM-DD`) of an epoch-millisecond instant. */
@RaftCoreInternalApi fun agentUsageUtcDay(epochMillis: Long): String =
    agentUsageFormatUtcEpochDay(floorDiv(epochMillis, MillisPerDay))

/** Parses a strict `YYYY-MM-DD` UTC date to its Unix epoch day, or null if malformed. */
@RaftCoreInternalApi fun agentUsageParseUtcEpochDay(day: String): Long? {
    val match = AgentUsageUtcDayRegex.matchEntire(day.trim()) ?: return null
    val year = match.groupValues[1].toIntOrNull() ?: return null
    val month = match.groupValues[2].toIntOrNull() ?: return null
    val dom = match.groupValues[3].toIntOrNull() ?: return null
    if (month !in 1..12 || dom !in 1..31) return null
    val epochDay = raftEpochDayForDate(year, month, dom)
    val roundTrip = civilDateFromEpochDay(epochDay)
    if (roundTrip.year != year || roundTrip.month != month || roundTrip.day != dom) return null
    return epochDay
}

/** Shifts a `YYYY-MM-DD` UTC day by [delta] days. Returns [day] unchanged if malformed. */
@RaftCoreInternalApi fun agentUsageAddDays(day: String, delta: Int): String {
    val epochDay = agentUsageParseUtcEpochDay(day) ?: return day
    return agentUsageFormatUtcEpochDay(epochDay + delta.toLong())
}

/**
 * Day of week for a UTC `YYYY-MM-DD` date: `0` = Sunday, `1` = Monday, ..., `6` = Saturday.
 * Unix epoch day 0 (1970-01-01) was a Thursday (`4`).
 */
@RaftCoreInternalApi fun agentUsageWeekdayIndex(day: String): Int? {
    val epochDay = agentUsageParseUtcEpochDay(day) ?: return null
    return floorMod(epochDay + 4L, 7L).toInt()
}

/**
 * The days a range asks for: the range itself and as many days before it, capped at
 * [AGENT_USAGE_MAX_RANGE_DAYS] (90) to match the server limit.
 */
@RaftCoreInternalApi fun agentUsageRequestWindow(rangeDays: Int, today: String): AgentUsageDateWindow {
    val safeRange = rangeDays.coerceAtLeast(1)
    val span = minOf(2 * safeRange, AGENT_USAGE_MAX_RANGE_DAYS)
    return AgentUsageDateWindow(
        from = agentUsageAddDays(today, -(span - 1)),
        to = today,
    )
}

/** The range's days, oldest first, ending on [today]. */
@RaftCoreInternalApi fun agentUsageRangeDays(range: AgentUsageRange, today: String): List<String> =
    List(range.days) { index -> agentUsageAddDays(today, index - range.days + 1) }

private fun addReported(sum: Long?, value: Long?): Long? =
    if (value == null) sum else (sum ?: 0L) + value

private fun sumReported(vararg values: Long?): Long? {
    var total: Long? = null
    for (v in values) {
        total = addReported(total, v)
    }
    return total
}

@RaftCoreInternalApi fun sumAgentUsageRows(rows: List<AgentUsageRow>): AgentUsageSums {
    var inputNew: Long? = null
    var cacheRead: Long? = null
    var cacheWrite: Long? = null
    var output: Long? = null
    var reasoning: Long? = null
    var turns = 0L
    for (row in rows) {
        inputNew = addReported(inputNew, row.inputNew)
        cacheRead = addReported(cacheRead, row.cacheRead)
        cacheWrite = addReported(cacheWrite, row.cacheWrite)
        output = addReported(output, row.output)
        reasoning = addReported(reasoning, row.reasoningOutput)
        turns += row.turns
    }
    return AgentUsageSums(
        inputNew = inputNew,
        cacheRead = cacheRead,
        cacheWrite = cacheWrite,
        output = output,
        reasoning = reasoning,
        turns = turns,
    )
}

/** Every reported kind (reasoning is part of output, never added to it); null when none reported. */
@RaftCoreInternalApi fun totalAgentUsageTokens(sums: AgentUsageSums): Long? =
    sumReported(sums.inputNew, sums.cacheRead, sums.cacheWrite, sums.output)

/** What the bars and headline count: new input + cache write + output, plus cache reads when asked. */
@RaftCoreInternalApi fun chartAgentUsageTokens(sums: AgentUsageSums, withCacheReads: Boolean): Long? =
    if (withCacheReads) {
        sumReported(sums.inputNew, sums.cacheWrite, sums.output, sums.cacheRead)
    } else {
        sumReported(sums.inputNew, sums.cacheWrite, sums.output)
    }

/** Cache reads as a share of all input (new + cache write + cache read); null when unreported or 0 input. */
@RaftCoreInternalApi fun cacheShareOfInput(sums: AgentUsageSums): Double? {
    val cacheRead = sums.cacheRead ?: return null
    val input = (sums.inputNew ?: 0L) + (sums.cacheWrite ?: 0L) + cacheRead
    return if (input > 0L) cacheRead.toDouble() / input.toDouble() else null
}

/** Average total tokens per turn; null when total is unreported or turns <= 0. */
@RaftCoreInternalApi fun tokensPerTurn(sums: AgentUsageSums): Double? {
    val total = totalAgentUsageTokens(sums) ?: return null
    return if (sums.turns > 0L) total.toDouble() / sums.turns.toDouble() else null
}

/** Relative change against a base: null when base is null or <= 0, or now is null. */
@RaftCoreInternalApi fun relativeChange(now: Long?, base: Long?): Double? {
    if (now == null || base == null || base <= 0L) return null
    return now.toDouble() / base.toDouble() - 1.0
}

/** Later of the server's and agent's `since`; null when neither is known. */
@RaftCoreInternalApi fun usageSince(response: AgentUsageResponse, agentId: String): String? {
    val agentSince = response.agents.firstOrNull { it.agentId == agentId }?.since
    return listOfNotNull(
        response.since?.takeIf { it.isNotBlank() },
        agentSince?.takeIf { it.isNotBlank() },
    ).maxOrNull()
}

/** Whether any turn of the agent has reported usage (matches web `!== false`). */
@RaftCoreInternalApi fun usageReported(response: AgentUsageResponse, agentId: String): Boolean =
    response.agents.firstOrNull { it.agentId == agentId }?.usageReported != false

/** Why the agent has no usage reported (read when [usageReported] is false). */
@RaftCoreInternalApi fun usageUnreportedReason(response: AgentUsageResponse, agentId: String): UsageUnreportedReason {
    val agent = response.agents.firstOrNull { it.agentId == agentId }
    if (agent?.runtimeReportsUsage == false) return UsageUnreportedReason.RuntimeNoUsage
    return when (agent?.computerReportsUsage) {
        ComputerReportsUsageWire.False -> UsageUnreportedReason.ComputerTooOld
        ComputerReportsUsageWire.True -> UsageUnreportedReason.NotRun
        ComputerReportsUsageWire.Null -> UsageUnreportedReason.NoComputer
        ComputerReportsUsageWire.Unknown, null -> UsageUnreportedReason.Unknown
    }
}

/** The agent's latest context reading, when both used and positive window are present. */
@RaftCoreInternalApi fun usageContext(response: AgentUsageResponse, agentId: String): AgentUsageContextReading? {
    val entry = response.context.firstOrNull { it.agentId == agentId } ?: return null
    val used = entry.contextUsed ?: return null
    val window = entry.contextWindow?.takeIf { it > 0L } ?: return null
    return AgentUsageContextReading(used = used, window = window)
}

private fun modelsOf(rows: List<AgentUsageRow>, withCacheReads: Boolean): List<AgentUsageModel> {
    if (rows.isEmpty()) return emptyList()
    val grouped = LinkedHashMap<Pair<String?, String>, MutableList<AgentUsageRow>>()
    for (row in rows) {
        grouped.getOrPut(row.runtime to row.model) { mutableListOf() }.add(row)
    }
    return grouped.values
        .map { group ->
            AgentUsageModel(
                runtime = group.first().runtime,
                model = group.first().model,
                tokens = chartAgentUsageTokens(sumAgentUsageRows(group), withCacheReads),
            )
        }
        .sortedByDescending { it.tokens ?: 0L }
}

@RaftCoreInternalApi fun summarizeUsageRows(
    response: AgentUsageResponse,
    rows: List<AgentUsageRow>,
    since: String?,
    view: AgentUsageView,
    withCacheReads: Boolean,
): AgentUsageFigures {
    val today = response.to
    fun hasData(day: String): Boolean = day >= response.from && (since == null || day >= since)
    val partialDay = parseRaftTimestampEpochMillisOrNull(response.asOf, localOffsetMinutes = 0)?.let(::agentUsageUtcDay)
    val rowsByDay = rows.groupBy { it.day }
    val days =
        agentUsageRangeDays(view.range, today).map { day ->
            AgentUsageDay(
                day = day,
                noData = !hasData(day),
                partial = day == partialDay,
                sums = sumAgentUsageRows(rowsByDay[day].orEmpty()),
            )
        }
    val shownDays = if (view.day != null) listOf(view.day) else days.map { it.day }
    val shownDaySet = shownDays.toSet()
    val shownRows = rows.filter { it.day in shownDaySet }
    val noData = shownDays.none(::hasData)

    fun sumsOver(period: List<String>): AgentUsageSums {
        val periodSet = period.toSet()
        val periodRows = rows.filter { it.day in periodSet }
        val periodSums = sumAgentUsageRows(periodRows)
        return if (periodRows.isEmpty() && period.any(::hasData)) {
            periodSums.copy(inputNew = 0L, cacheRead = 0L, output = 0L)
        } else {
            periodSums
        }
    }

    val sums = sumsOver(shownDays)
    var compared: Long? = null
    var base: Long? = null
    var baseDay: String? = null
    val fullDays = shownDays.filter { it != partialDay }
    if (view.day != null && fullDays.isNotEmpty()) {
        val prior = agentUsageAddDays(view.day, -7)
        baseDay = prior
        if (hasData(prior)) {
            compared = chartAgentUsageTokens(sums, withCacheReads)
            base = chartAgentUsageTokens(sumsOver(listOf(prior)), withCacheReads)
        }
    } else if (view.day == null && fullDays.isNotEmpty()) {
        val priorDays = fullDays.map { agentUsageAddDays(it, -fullDays.size) }
        if (hasData(priorDays.first())) {
            compared = chartAgentUsageTokens(sumsOver(fullDays), withCacheReads)
            base = chartAgentUsageTokens(sumsOver(priorDays), withCacheReads)
        }
    }

    return AgentUsageFigures(
        days = days,
        since = since,
        asOf = response.asOf,
        noData = noData,
        sums = sums,
        models = modelsOf(shownRows, withCacheReads),
        compared = compared,
        base = base,
        baseDay = baseDay,
    )
}

@RaftCoreInternalApi fun summarizeAgentUsage(
    response: AgentUsageResponse,
    agentId: String,
    view: AgentUsageView,
    withCacheReads: Boolean,
): AgentUsageSummary {
    if (!usageReported(response, agentId)) {
        return AgentUsageSummary.Unsupported(usageUnreportedReason(response, agentId))
    }
    val rows = response.rows.filter { it.agentId == agentId }
    if (rows.isEmpty()) return AgentUsageSummary.Empty
    return AgentUsageSummary.Ready(
        figures = summarizeUsageRows(response, rows, usageSince(response, agentId), view, withCacheReads),
        context = usageContext(response, agentId),
    )
}

// ── Formatting helpers (pure, portable across Android/iOS/Desktop) ──

private fun formatDecimalCompact(value: Double, maxFractionDigits: Int): String {
    if (maxFractionDigits <= 0) return value.roundToLong().toString()
    val scale = 10.0.pow(maxFractionDigits)
    val scaled = (value * scale).roundToLong()
    val intScale = scale.roundToLong()
    val whole = scaled / intScale
    val frac = abs(scaled % intScale)
    if (frac == 0L) return whole.toString()
    val fracStr = frac.toString().padStart(maxFractionDigits, '0').trimEnd('0')
    return "$whole.$fracStr"
}

/**
 * Compact token formatter mirroring web `Intl.NumberFormat({ notation: "compact", maximumFractionDigits: tokens >= 1e9 ? 2 : 1 })`:
 * `0`, `420`, `1.2K`, `12K`, `3.4M`, `1.25B`.
 */
@RaftCoreInternalApi fun formatAgentUsageTokens(tokens: Double): String {
    val magnitude = abs(tokens)
    val sign = if (tokens < 0) "-" else ""
    return when {
        magnitude >= 999_950_000.0 -> "${sign}${formatDecimalCompact(magnitude / 1_000_000_000.0, 2)}B"
        magnitude >= 999_950.0 -> "${sign}${formatDecimalCompact(magnitude / 1_000_000.0, 1)}M"
        magnitude >= 1_000.0 -> "${sign}${formatDecimalCompact(magnitude / 1_000.0, 1)}K"
        else -> "${sign}${magnitude.roundToLong()}"
    }
}

@RaftCoreInternalApi fun formatAgentUsageTokens(tokens: Long): String = formatAgentUsageTokens(tokens.toDouble())

/** Percentage formatter mirroring web `Intl.NumberFormat({ style: "percent", maximumFractionDigits: digits })`. */
@RaftCoreInternalApi fun formatAgentUsagePercent(share: Double, maxFractionDigits: Int = 1): String =
    "${formatDecimalCompact(share * 100.0, maxFractionDigits)}%"

/** Relative-change formatter mirroring web `signDisplay: "exceptZero", maximumFractionDigits: 0`. */
@RaftCoreInternalApi fun formatAgentUsageChange(change: Double): String {
    val pct = (change * 100.0).roundToLong()
    return when {
        pct > 0L -> "+$pct%"
        pct < 0L -> "$pct%"
        else -> "0%"
    }
}

// ── Wire decoding (coerceInputValues = false so explicit JSON null stays JsonNull) ──

private val agentUsageWireJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = false
}

private val AGENT_USAGE_OMITTED_SENTINEL: JsonElement = JsonArray(emptyList())

@Serializable
private data class AgentUsageRowDto(
    val day: String,
    val agent_id: String,
    val runtime: String? = null,
    val model: String = "",
    val input_new: Long? = null,
    val cache_read: Long? = null,
    val cache_write: Long? = null,
    val output: Long? = null,
    val reasoning_output: Long? = null,
    val turns: Long = 0L,
) {
    fun toDomain(): AgentUsageRow =
        AgentUsageRow(
            day = day,
            agentId = agent_id,
            runtime = runtime?.takeIf { it.isNotBlank() },
            model = model,
            inputNew = input_new,
            cacheRead = cache_read,
            cacheWrite = cache_write,
            output = output,
            reasoningOutput = reasoning_output,
            turns = turns,
        )
}

@Serializable
private data class AgentUsageContextDto(
    val agent_id: String,
    val context_used: Long? = null,
    val context_window: Long? = null,
    val at: String = "",
) {
    fun toDomain(): AgentUsageContextEntry =
        AgentUsageContextEntry(
            agentId = agent_id,
            contextUsed = context_used,
            contextWindow = context_window,
            at = at,
        )
}

@Serializable
private data class AgentUsageAgentDto(
    val agent_id: String,
    val since: String? = null,
    val usage_reported: Boolean = true,
    val computer_reports_usage: JsonElement = AGENT_USAGE_OMITTED_SENTINEL,
    val runtime_reports_usage: Boolean? = null,
) {
    fun toDomain(): AgentUsageAgentEntry {
        val computerWire =
            when (val el = computer_reports_usage) {
                is JsonNull -> ComputerReportsUsageWire.Null
                is JsonPrimitive ->
                    if (!el.isString) {
                        when (el.booleanOrNull) {
                            true -> ComputerReportsUsageWire.True
                            false -> ComputerReportsUsageWire.False
                            null -> ComputerReportsUsageWire.Unknown
                        }
                    } else {
                        ComputerReportsUsageWire.Unknown
                    }
                else -> ComputerReportsUsageWire.Unknown
            }
        return AgentUsageAgentEntry(
            agentId = agent_id,
            since = since?.takeIf { it.isNotBlank() },
            usageReported = usage_reported,
            computerReportsUsage = computerWire,
            runtimeReportsUsage = runtime_reports_usage,
        )
    }
}

@Serializable
private data class AgentUsageResponseDto(
    val from: String,
    val to: String,
    val as_of: String,
    val since: String? = null,
    val visible_agents: Int = 0,
    val agents: List<AgentUsageAgentDto> = emptyList(),
    val rows: List<AgentUsageRowDto> = emptyList(),
    val context: List<AgentUsageContextDto> = emptyList(),
) {
    fun toDomain(): AgentUsageResponse? {
        if (from.isBlank() || to.isBlank()) return null
        return AgentUsageResponse(
            from = from,
            to = to,
            asOf = as_of,
            since = since?.takeIf { it.isNotBlank() },
            visibleAgents = visible_agents,
            agents = agents.map { it.toDomain() },
            rows = rows.map { it.toDomain() },
            context = context.map { it.toDomain() },
        )
    }
}

@RaftCoreInternalApi fun decodeAgentUsageResponse(body: String): AgentUsageResponse? =
    try {
        agentUsageWireJson.decodeFromString(AgentUsageResponseDto.serializer(), body).toDomain()
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
