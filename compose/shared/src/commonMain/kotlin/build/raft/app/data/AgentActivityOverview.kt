package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/*
 * Agent Activity tab "Overview" (slock task #17): one summary per finished agent turn.
 *
 * Wire contract, verified against botiverse/slock (see the parser test for the pinned SHA):
 * - `GET /agents/:id/activity-summaries?limit=N&before=<next>` (packages/server/src/routes/agents.ts)
 *   answers `{ summaries: ActivityTurnSummaryView[], next?: "<endedAtMs>:<row uuid>" }`, newest first,
 *   behind `canInspectAgentPrivateSurfaces` (same gate as `/activity-log`).
 * - A summary is packages/shared/src/activityTurnSummary.ts `ActivityTurnSummary` plus the stored row
 *   `id` (agentActivityLogService `StoredTurnSummary`). The server replaces a trigger the viewer cannot
 *   see with `{ hidden: "private_channel" | "dm" }` (packages/server/src/services/activityTurnSummaryView.ts),
 *   so per-channel access is already filtered: the client renders what comes back.
 * - The last reply is read by id through `GET /messages/context/:messageId?before=0&after=0`, under
 *   the message's own visibility rules.
 */

private val activityOverviewJson = Json {
    ignoreUnknownKeys = true
}

@Serializable
internal data class ActivityTurnSummaryPageWire(
    val summaries: List<kotlinx.serialization.json.JsonElement>,
    val next: String? = null,
)

/** `ActivityTurnTrigger` or the server's hidden view of it (`{ hidden }`). */
@Serializable
internal data class ActivityTurnTriggerWire(
    val target: String? = null,
    val channelId: String? = null,
    val sender: String? = null,
    val messageId: String? = null,
    val hidden: String? = null,
)

@Serializable
internal data class ActivityTurnActionCountWire(
    val kind: String,
    val count: Int,
)

@Serializable
internal data class ActivityTurnProblemWire(
    val kind: String,
    val reason: String,
    val exitCode: Int? = null,
    val at: Long,
)

@Serializable
internal data class ActivityTurnSummaryWire(
    /** Row id from the server (absent from older servers). */
    val id: String? = null,
    val v: Int = 1,
    val startedAt: Long,
    val endedAt: Long,
    val outcome: String,
    val trigger: ActivityTurnTriggerWire? = null,
    val actions: List<ActivityTurnActionCountWire>,
    val toolCalls: Int,
    val problems: List<ActivityTurnProblemWire>,
    val problemCount: Int,
    val messagesSent: Int,
    val lastReplyMessageId: String? = null,
)

/** slock `ACTIVITY_TURN_OUTCOMES`. */
enum class AgentActivityTurnOutcome(val wireValue: String) {
    Completed("completed"),
    Failed("failed"),
    Interrupted("interrupted"),
    Unknown("unknown");

    companion object {
        /** A newer server's outcome reads as "ended, outcome unknown" rather than dropping the turn. */
        fun fromWire(value: String): AgentActivityTurnOutcome =
            entries.firstOrNull { it.wireValue == value } ?: Unknown
    }
}

/** slock `ACTIVITY_ACTION_KINDS`, in the shared order. */
enum class AgentActivityActionKind(val wireValue: String) {
    RunCommand("run_command"),
    ReadFile("read_file"),
    EditFile("edit_file"),
    Search("search"),
    Web("web"),
    Fetch("fetch"),
    Subagent("subagent"),
    Mcp("mcp"),
    Wait("wait"),
    Other("other"),
    SendMessage("send_message"),
    ReadMessages("read_messages"),
    RaftAction("raft_action"),
    VersionControl("version_control"),
    RunChecks("run_checks");

    companion object {
        /** A kind this client does not know yet is counted as "used a tool". */
        fun fromWire(value: String): AgentActivityActionKind =
            entries.firstOrNull { it.wireValue == value } ?: Other
    }
}

/** slock `ACTIVITY_PROBLEM_REASONS`. */
enum class AgentActivityProblemReason(val wireValue: String) {
    CallFailed("call_failed"),
    ExitNonzero("exit_nonzero"),
    SendHeld("send_held");

    companion object {
        fun fromWire(value: String): AgentActivityProblemReason =
            entries.firstOrNull { it.wireValue == value } ?: CallFailed
    }
}

data class AgentActivityActionCount(val kind: AgentActivityActionKind, val count: Int)

data class AgentActivityTurnProblem(
    val kind: AgentActivityActionKind,
    val reason: AgentActivityProblemReason,
    val exitCode: Int?,
    val atEpochMillis: Long,
) {
    /**
     * Tests or checks that ran and reported failures are ordinary work, not a step that went
     * wrong (web `countsAsProblem`).
     */
    val countsAsProblem: Boolean
        get() = !(reason == AgentActivityProblemReason.ExitNonzero && kind == AgentActivityActionKind.RunChecks)
}

sealed interface AgentActivityTurnTrigger {
    data class Visible(
        val target: String?,
        val channelId: String?,
        val sender: String?,
        val messageId: String?,
    ) : AgentActivityTurnTrigger

    /** The viewer cannot see where the waking message came from: only the kind of place. */
    data class Hidden(val place: HiddenPlace) : AgentActivityTurnTrigger

    enum class HiddenPlace { PrivateChannel, Dm }
}

data class AgentActivityTurn(
    val id: String?,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
    val outcome: AgentActivityTurnOutcome,
    val trigger: AgentActivityTurnTrigger?,
    val actions: List<AgentActivityActionCount>,
    val toolCalls: Int,
    /** At most the server's problem limit, oldest first; [problemCount] is the full count. */
    val problems: List<AgentActivityTurnProblem>,
    val problemCount: Int,
    val messagesSent: Int,
    val lastReplyMessageId: MessageId?,
) {
    /** Stable list key: the row id, or the time span for an older server. */
    val key: String
        get() = id ?: "$startedAtEpochMillis-$endedAtEpochMillis"

    /** Web `turnProblemCount`: steps that went wrong, not counting failed checks. */
    val problemStepCount: Int
        get() = (problemCount - problems.count { !it.countsAsProblem }).coerceAtLeast(0)
}

data class AgentActivityTurnPage(
    val turns: List<AgentActivityTurn>,
    /** Opaque cursor for the next (older) page; null on the last page. */
    val next: String?,
)

/**
 * Parses `GET /agents/:id/activity-summaries`. Returns null when the body is not the
 * `{ summaries: [...] }` envelope. A summary that does not decode is dropped (with the rest kept)
 * so one malformed row cannot blank the whole overview.
 */
fun parseSharedAgentActivityTurnPage(
    bodyText: String,
    diagnostics: (String) -> Unit = {},
): AgentActivityTurnPage? {
    val root = runCatching { activityOverviewJson.parseToJsonElement(bodyText) }.getOrNull() as? JsonObject
        ?: return null
    if (root["summaries"] !is JsonArray) return null
    val page = runCatching {
        activityOverviewJson.decodeFromJsonElement(ActivityTurnSummaryPageWire.serializer(), root)
    }.getOrNull() ?: return null
    val turns = page.summaries.mapIndexedNotNull { index, element ->
        runCatching {
            activityOverviewJson.decodeFromJsonElement(ActivityTurnSummaryWire.serializer(), element)
        }.getOrNull()?.toDomain()
            ?: run {
                diagnostics("activity_summaries_row_dropped index=$index")
                null
            }
    }
    return AgentActivityTurnPage(turns = turns, next = page.next?.takeIf { it.isNotBlank() })
}

private fun ActivityTurnSummaryWire.toDomain(): AgentActivityTurn =
    AgentActivityTurn(
        id = id?.takeIf { it.isNotBlank() },
        startedAtEpochMillis = startedAt,
        endedAtEpochMillis = endedAt,
        outcome = AgentActivityTurnOutcome.fromWire(outcome),
        trigger = trigger?.toDomain(),
        actions = actions.map { AgentActivityActionCount(AgentActivityActionKind.fromWire(it.kind), it.count) },
        toolCalls = toolCalls,
        problems = problems.map {
            AgentActivityTurnProblem(
                kind = AgentActivityActionKind.fromWire(it.kind),
                reason = AgentActivityProblemReason.fromWire(it.reason),
                exitCode = it.exitCode,
                atEpochMillis = it.at,
            )
        },
        problemCount = problemCount,
        messagesSent = messagesSent,
        lastReplyMessageId = lastReplyMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
    )

private fun ActivityTurnTriggerWire.toDomain(): AgentActivityTurnTrigger =
    when (hidden) {
        null ->
            AgentActivityTurnTrigger.Visible(
                target = target,
                channelId = channelId,
                sender = sender,
                messageId = messageId,
            )
        "dm" -> AgentActivityTurnTrigger.Hidden(AgentActivityTurnTrigger.HiddenPlace.Dm)
        // "private_channel", or a hidden kind this client does not know: never show a name.
        else -> AgentActivityTurnTrigger.Hidden(AgentActivityTurnTrigger.HiddenPlace.PrivateChannel)
    }

/** Who woke a turn, as web `formatTurnTrigger` words it (the caller picks the localized sentence). */
sealed interface AgentActivityTriggerDescription {
    data object HiddenDm : AgentActivityTriggerDescription
    data object HiddenPrivateChannel : AgentActivityTriggerDescription
    data object AppEvent : AgentActivityTriggerDescription
    /** [peer] keeps its `@`. */
    data class DmFrom(val peer: String) : AgentActivityTriggerDescription
    /** A waking message whose sender is unknown. [channel] is the target's channel part, e.g. `#proj`. */
    data class Target(val channel: String) : AgentActivityTriggerDescription
    /** [sender] carries `@`. */
    data class Message(val sender: String, val channel: String) : AgentActivityTriggerDescription
    data class Thread(val sender: String, val channel: String) : AgentActivityTriggerDescription
}

private val dmTargetPattern = Regex("^dm:(@[^:]+)")

fun AgentActivityTurnTrigger?.describe(): AgentActivityTriggerDescription? {
    when (this) {
        null -> return null
        is AgentActivityTurnTrigger.Hidden ->
            return when (place) {
                AgentActivityTurnTrigger.HiddenPlace.Dm -> AgentActivityTriggerDescription.HiddenDm
                AgentActivityTurnTrigger.HiddenPlace.PrivateChannel -> AgentActivityTriggerDescription.HiddenPrivateChannel
            }
        is AgentActivityTurnTrigger.Visible -> {
            val target = target?.takeIf { it.isNotEmpty() } ?: return null
            if (target.startsWith("agent-event:")) return AgentActivityTriggerDescription.AppEvent
            dmTargetPattern.find(target)?.let { return AgentActivityTriggerDescription.DmFrom(it.groupValues[1]) }
            val parts = target.split(":")
            val channel = parts.firstOrNull() ?: target
            val threadShortId = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
            val senderHandle = sender?.takeIf { it.isNotEmpty() }?.let { "@$it" }
                ?: return AgentActivityTriggerDescription.Target(channel)
            return if (threadShortId != null) {
                AgentActivityTriggerDescription.Thread(senderHandle, channel)
            } else {
                AgentActivityTriggerDescription.Message(senderHandle, channel)
            }
        }
    }
}

/** A turn's last reply, as far as the viewer may read it. */
sealed interface AgentActivityTurnReply {
    data class Loaded(val content: String, val createdAtEpochMillis: Long?) : AgentActivityTurnReply

    /** Not found or not readable by this viewer: web shows one line for these (and for failures). */
    data object Unavailable : AgentActivityTurnReply

    /** The request failed for a passing reason (network, 5xx, empty body): worth asking again. */
    data object Failed : AgentActivityTurnReply
}

const val AGENT_ACTIVITY_OVERVIEW_PAGE_SIZE: Int = 30

/** Shared repository for the Activity overview, over the shared network boundary. */
interface AgentActivityOverviewRepository {
    suspend fun loadTurns(
        priority: DataRequestPriority,
        agentId: MemberId,
        before: String? = null,
        limit: Int = AGENT_ACTIVITY_OVERVIEW_PAGE_SIZE,
    ): DataResult<AgentActivityTurnPage>

    suspend fun loadReply(priority: DataRequestPriority, messageId: MessageId): AgentActivityTurnReply
}

class RealAgentActivityOverviewRepository(
    private val networkClient: RaftNetworkClient,
    private val logger: (String) -> Unit = {},
) : AgentActivityOverviewRepository {
    override suspend fun loadTurns(
        priority: DataRequestPriority,
        agentId: MemberId,
        before: String?,
        limit: Int,
    ): DataResult<AgentActivityTurnPage> {
        val response = networkClient.request(
            priority = priority,
            path = "agents/${agentId.value}/activity-summaries",
            query = listOfNotNull("limit" to limit.toString(), before?.let { "before" to it }),
        )
        if (response.code == 401 || response.code == 403) {
            return DataResult.NoPermission(PermissionBlocker(code = "activity_summaries_${response.code}"))
        }
        if (!response.isSuccessful) {
            return DataResult.Error(
                ScreenError(
                    code = "activity_summaries_${response.code}",
                    retryable = response.code == 0 || response.code >= 500,
                )
            )
        }
        val page = response.bodyText?.let { body ->
            parseSharedAgentActivityTurnPage(body) { message -> logger("agent_activity_overview $message") }
        } ?: return DataResult.Error(ScreenError("activity_summaries_contract_mismatch", retryable = false))
        return DataResult.Content(page)
    }

    override suspend fun loadReply(priority: DataRequestPriority, messageId: MessageId): AgentActivityTurnReply {
        val response = networkClient.request(
            priority = priority,
            path = "messages/context/${messageId.value}",
            query = listOf("before" to "0", "after" to "0"),
        )
        if (response.code == 401 || response.code == 403 || response.code == 404) {
            return AgentActivityTurnReply.Unavailable
        }
        val body = response.bodyText?.takeIf { response.isSuccessful && it.isNotBlank() }
            ?: return AgentActivityTurnReply.Failed
        val payload = parseSharedMessageContext(body, fallbackThreadId = ThreadId(messageId.value))
        val targetId = payload.targetMessageId ?: messageId
        val message = payload.messages.firstOrNull { it.id == targetId } ?: return AgentActivityTurnReply.Unavailable
        return AgentActivityTurnReply.Loaded(content = message.body, createdAtEpochMillis = message.createdAtEpochMillis)
    }
}
