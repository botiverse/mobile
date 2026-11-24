package build.raft.app.data

/**
 * One event a connected app sent to an agent (slock shared `AgentAppEventSummary`), as the Agent
 * panel's Apps tab lists it (web AgentAppAccessTab › App events). [status] is null for a status
 * this build does not know.
 */
data class AgentAppEvent(
    val id: String,
    val appClientId: String,
    val appName: String,
    val kind: String,
    val summary: String,
    val status: AgentAppEventStatus?,
    /** ISO-8601 UTC. */
    val createdAt: String
)

/** The display status: an event not delivered before it expires reads as [Expired]. */
enum class AgentAppEventStatus(val wireValue: String) {
    Queued("queued"),
    Delivered("delivered"),
    Expired("expired")
}

/** A page of [AgentAppEvent]s, newest first; [nextCursor] reads the next (older) page. */
data class AgentAppEventPage(
    val events: List<AgentAppEvent>,
    val nextCursor: String?
)

/** An event's app-supplied payload, pretty-printed JSON (shown as inert text, never as links). */
data class AgentAppEventDetail(
    val eventId: String,
    val payloadText: String
)
