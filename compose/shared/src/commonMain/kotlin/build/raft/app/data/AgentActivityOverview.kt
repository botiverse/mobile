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
 * - Newer servers (botiverse/slock 24873fd5be37) add, all optional and additive: `current` on the
 *   first page (the running turn, `running: true`, no outcome), per turn `sentMessageIds` /
 *   `sentMessageCount` / `hiddenSentCount` and `appEvents` / `appEventCount`
 *   (`ActivityTurnAppEventView`), and on a visible trigger `display` (`ActivityTurnTriggerDisplay`,
 *   names from the server's own rows) and `appEvent`; a trigger the server cannot bear out comes
 *   back `{ unresolved: true }` (packages/server/src/services/activityTurnSummaryView.ts).
 */

private val activityOverviewJson = Json {
    ignoreUnknownKeys = true
}

@Serializable
internal data class ActivityTurnSummaryPageWire(
    val summaries: List<kotlinx.serialization.json.JsonElement>,
    val next: String? = null,
    /** The running turn (`RunningActivityTurnSummary`), first page only. */
    val current: kotlinx.serialization.json.JsonElement? = null,
)

/** `ActivityTurnTriggerDisplay`: `channel` / `thread` with `channelName`, or `dm`. Handles without `@`. */
@Serializable
internal data class ActivityTurnTriggerDisplayWire(
    val kind: String,
    val channelName: String? = null,
    val senderName: String? = null,
    val peerName: String? = null,
)

/** `ActivityTurnTriggerAppEvent`. */
@Serializable
internal data class ActivityTurnTriggerAppEventWire(
    val appName: String,
    val summary: String,
)

/** `ActivityTurnTrigger` or the server's view of it (`{ hidden }`, `{ unresolved: true }`). */
@Serializable
internal data class ActivityTurnTriggerWire(
    val target: String? = null,
    val channelId: String? = null,
    val sender: String? = null,
    val messageId: String? = null,
    val hidden: String? = null,
    val unresolved: Boolean? = null,
    val display: ActivityTurnTriggerDisplayWire? = null,
    val appEvent: ActivityTurnTriggerAppEventWire? = null,
)

/** `ActivityTurnAppEventView`. */
@Serializable
internal data class ActivityTurnAppEventWire(
    val id: String,
    val appName: String,
    val clientKey: String,
    val logoUrl: String? = null,
    val summary: String,
    val deliveredAt: Long,
    val kind: String,
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
    /** Absent only on the running turn (`current`). */
    val outcome: String? = null,
    val running: Boolean? = null,
    val trigger: ActivityTurnTriggerWire? = null,
    val actions: List<ActivityTurnActionCountWire>,
    val toolCalls: Int,
    val problems: List<ActivityTurnProblemWire>,
    val problemCount: Int,
    val messagesSent: Int,
    val lastReplyMessageId: String? = null,
    val sentMessageIds: List<String>? = null,
    val sentMessageCount: Int? = null,
    val hiddenSentCount: Int? = null,
    val appEvents: List<ActivityTurnAppEventWire>? = null,
    val appEventCount: Int? = null,
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
     * Web `countsAsProblem`: a command that exits non-zero (failing checks included) is ordinary
     * development, and a send held until newer messages are read is the normal flow. Only a tool
     * call that itself failed counts as a step that went wrong.
     */
    val countsAsProblem: Boolean
        get() = reason != AgentActivityProblemReason.ExitNonzero && !isRoutineHold

    /** Web `isRoutineHold`: neither shown nor counted (older summaries still record it). */
    val isRoutineHold: Boolean
        get() = reason == AgentActivityProblemReason.SendHeld
}

sealed interface AgentActivityTurnTrigger {
    data class Visible(
        val target: String?,
        val channelId: String?,
        val sender: String?,
        val messageId: String?,
        /** Place and sender as the server names them from its own rows; null from older servers. */
        val display: Display? = null,
        /** The app event that woke the turn (`agent-event:<id8>`), when the server resolved it. */
        val appEvent: AppEvent? = null,
    ) : AgentActivityTurnTrigger

    /** The viewer cannot see where the waking message came from: only the kind of place. */
    data class Hidden(val place: HiddenPlace) : AgentActivityTurnTrigger

    /** The server could not match the trigger to its channel and message: none of its text. */
    data object Unresolved : AgentActivityTurnTrigger

    enum class HiddenPlace { PrivateChannel, Dm }

    /** `ActivityTurnTriggerDisplay`; handles without `@`. */
    sealed interface Display {
        data class Channel(val channelName: String, val senderName: String?, val thread: Boolean) : Display
        data class Dm(val senderName: String?, val peerName: String?) : Display
    }

    data class AppEvent(val appName: String, val summary: String)
}

/** One app notification delivered to the agent during a turn (`ActivityTurnAppEventView`). */
data class AgentActivityAppEvent(
    val id: String,
    val appName: String,
    val clientKey: String,
    val logoUrl: String?,
    val summary: String,
    val deliveredAtEpochMillis: Long,
)

data class AgentActivityTurn(
    val id: String?,
    val startedAtEpochMillis: Long,
    /** For the running turn: when the server read it. */
    val endedAtEpochMillis: Long,
    /** [AgentActivityTurnOutcome.Unknown] while [running]. */
    val outcome: AgentActivityTurnOutcome,
    val trigger: AgentActivityTurnTrigger?,
    val actions: List<AgentActivityActionCount>,
    val toolCalls: Int,
    /** At most the server's problem limit, oldest first; [problemCount] is the full count. */
    val problems: List<AgentActivityTurnProblem>,
    val problemCount: Int,
    val messagesSent: Int,
    val lastReplyMessageId: MessageId?,
    /** Every message the turn sent that the viewer can see, in order (capped); null from older servers. */
    val sentMessageIds: List<MessageId>? = null,
    /** How many the viewer can see (the id list is capped). */
    val sentMessageCount: Int? = null,
    /** Sent where the viewer cannot see: counted, never named. */
    val hiddenSentCount: Int = 0,
    /** App notifications delivered in the turn, oldest first (capped). */
    val appEvents: List<AgentActivityAppEvent> = emptyList(),
    val appEventCount: Int? = null,
    /** The turn still running (the first page's `current`). */
    val running: Boolean = false,
) {
    /** Stable list key: the row id, or the time span for an older server; the running turn has its own. */
    val key: String
        get() = if (running) "running:${id ?: startedAtEpochMillis}" else id ?: "$startedAtEpochMillis-$endedAtEpochMillis"

    /**
     * Web `turnProblemCount`: listed steps that went wrong. What lies past the list's cap is of
     * unknown kind and not counted.
     */
    val problemStepCount: Int
        get() = problems.count { it.countsAsProblem }
}

data class AgentActivityTurnPage(
    /** Finished turns, newest first. */
    val turns: List<AgentActivityTurn>,
    /** Opaque cursor for the next (older) page; null on the last page. */
    val next: String?,
    /** The running turn, first page only (servers that fold it). */
    val current: AgentActivityTurn? = null,
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
        decodeTurnSummary(element)?.takeIf { it.outcome != null }?.toDomain(running = false)
            ?: run {
                diagnostics("activity_summaries_row_dropped index=$index")
                null
            }
    }
    val current = page.current?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let { element ->
        decodeTurnSummary(element)?.takeIf { it.running == true }?.toDomain(running = true)
            ?: run {
                diagnostics("activity_summaries_current_dropped")
                null
            }
    }
    return AgentActivityTurnPage(turns = turns, next = page.next?.takeIf { it.isNotBlank() }, current = current)
}

private fun decodeTurnSummary(element: kotlinx.serialization.json.JsonElement): ActivityTurnSummaryWire? =
    runCatching { activityOverviewJson.decodeFromJsonElement(ActivityTurnSummaryWire.serializer(), element) }.getOrNull()

private fun ActivityTurnSummaryWire.toDomain(running: Boolean): AgentActivityTurn =
    AgentActivityTurn(
        id = id?.takeIf { it.isNotBlank() },
        startedAtEpochMillis = startedAt,
        endedAtEpochMillis = endedAt,
        outcome = outcome?.let(AgentActivityTurnOutcome::fromWire) ?: AgentActivityTurnOutcome.Unknown,
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
        sentMessageIds = sentMessageIds?.filter { it.isNotBlank() }?.map(::MessageId),
        sentMessageCount = sentMessageCount,
        hiddenSentCount = (hiddenSentCount ?: 0).coerceAtLeast(0),
        appEvents = appEvents.orEmpty().map {
            AgentActivityAppEvent(
                id = it.id,
                appName = it.appName,
                clientKey = it.clientKey,
                logoUrl = it.logoUrl?.takeIf(String::isNotBlank),
                summary = it.summary,
                deliveredAtEpochMillis = it.deliveredAt,
            )
        },
        appEventCount = appEventCount,
        running = running,
    )

private fun ActivityTurnTriggerWire.toDomain(): AgentActivityTurnTrigger =
    when {
        hidden == "dm" -> AgentActivityTurnTrigger.Hidden(AgentActivityTurnTrigger.HiddenPlace.Dm)
        // "private_channel", or a hidden kind this client does not know: never show a name.
        hidden != null -> AgentActivityTurnTrigger.Hidden(AgentActivityTurnTrigger.HiddenPlace.PrivateChannel)
        unresolved == true -> AgentActivityTurnTrigger.Unresolved
        else ->
            AgentActivityTurnTrigger.Visible(
                target = target,
                channelId = channelId,
                sender = sender,
                messageId = messageId,
                display = display?.toDomain(),
                appEvent = appEvent?.let { AgentActivityTurnTrigger.AppEvent(it.appName, it.summary) },
            )
    }

/** A display kind this client does not know is no display: the trigger's own text is read. */
private fun ActivityTurnTriggerDisplayWire.toDomain(): AgentActivityTurnTrigger.Display? =
    when (kind) {
        "dm" -> AgentActivityTurnTrigger.Display.Dm(
            senderName = senderName?.takeIf(String::isNotBlank),
            peerName = peerName?.takeIf(String::isNotBlank),
        )
        "channel", "thread" -> channelName?.takeIf(String::isNotBlank)?.let {
            AgentActivityTurnTrigger.Display.Channel(
                channelName = it,
                senderName = senderName?.takeIf(String::isNotBlank),
                thread = kind == "thread",
            )
        }
        else -> null
    }

/** Who woke a turn, as web `formatTurnTrigger` words it (the caller picks the localized sentence). */
sealed interface AgentActivityTriggerDescription {
    data object HiddenDm : AgentActivityTriggerDescription
    data object HiddenPrivateChannel : AgentActivityTriggerDescription
    /** Web `activity.overview.trigger.unresolved`: "woken by a message". */
    data object Unresolved : AgentActivityTriggerDescription
    /** A DM whose other member is not named. */
    data object Dm : AgentActivityTriggerDescription
    data object AppEvent : AgentActivityTriggerDescription
    /** An app event the server named. */
    data class AppEventFrom(val app: String, val summary: String) : AgentActivityTriggerDescription
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
        AgentActivityTurnTrigger.Unresolved -> return AgentActivityTriggerDescription.Unresolved
        is AgentActivityTurnTrigger.Visible -> {
            display?.let { return it.describe() }
            val target = target?.takeIf { it.isNotEmpty() } ?: return null
            if (target.startsWith("agent-event:")) {
                // Pruned or ambiguous events, and older servers, keep the generic sentence.
                return appEvent?.let { AgentActivityTriggerDescription.AppEventFrom(it.appName, it.summary) }
                    ?: AgentActivityTriggerDescription.AppEvent
            }
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

/** Web `formatTriggerDisplay`: the sentence from the server's names. */
private fun AgentActivityTurnTrigger.Display.describe(): AgentActivityTriggerDescription =
    when (this) {
        is AgentActivityTurnTrigger.Display.Dm ->
            (senderName ?: peerName)?.let { AgentActivityTriggerDescription.DmFrom("@$it") }
                ?: AgentActivityTriggerDescription.Dm
        is AgentActivityTurnTrigger.Display.Channel -> {
            val channel = "#$channelName"
            val sender = senderName?.let { "@$it" }
            when {
                sender == null -> AgentActivityTriggerDescription.Target(channel)
                thread -> AgentActivityTriggerDescription.Thread(sender, channel)
                else -> AgentActivityTriggerDescription.Message(sender, channel)
            }
        }
    }

/**
 * The exact waking message, only where the viewer may see it (web `TriggerMessageLink`): a visible
 * trigger with both ids.
 */
fun AgentActivityTurnTrigger?.openableMessage(): Pair<String, MessageId>? {
    val visible = this as? AgentActivityTurnTrigger.Visible ?: return null
    val channelId = visible.channelId?.takeIf { it.isNotBlank() } ?: return null
    val messageId = visible.messageId?.takeIf { it.isNotBlank() } ?: return null
    return channelId to MessageId(messageId)
}

/** A turn's last reply, as far as the viewer may read it. */
sealed interface AgentActivityTurnReply {
    data class Loaded(
        val content: String,
        val createdAtEpochMillis: Long?,
        /** Where the message lives, for opening it (web `toMessage`). */
        val channelId: String? = null,
    ) : AgentActivityTurnReply

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
        return AgentActivityTurnReply.Loaded(
            content = message.body,
            createdAtEpochMillis = message.createdAtEpochMillis,
            channelId = (message.channelId ?: payload.channelId)?.value?.takeIf { it.isNotBlank() },
        )
    }
}
