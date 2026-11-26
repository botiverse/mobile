package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.ServerId
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Delete Computer on the person's own Computer when other people's agents are bound to it
 * (#wg-mobile-sync:#63; botiverse/slock #12882 / #14942 / #15305, web
 * `features/computer/othersAgentsApi.ts`, `useAgentOwnerNotify.ts`, `DeleteComputerDialog.tsx`,
 * server `routes/servers.ts` `revoke-computer-key` & `notify-agent-owners`):
 *
 * - `POST servers/:id/machines/:machineId/revoke-computer-key` `{ delete: true, forceAgentIds? }`
 *   revokes the Computer's key and removes the machine in one transaction while the live attacher
 *   is still its controller. While other people's live agents are on it the server answers 409
 *   `others_agents_on_computer` with `agents[]` and changes nothing. 409 `no_live_computer_key`
 *   (or a 200 without `deleted: true`) means the key is revoked and the caller falls back to
 *   `DELETE servers/:id/machines/:machineId`.
 * - `POST servers/:id/machines/:machineId/notify-agent-owners` `{ agentIds }` (1..200) sends each
 *   owner a system reminder to move their agent, at most once per agent per 24 hours.
 */

/** The most agent ids one `notify-agent-owners` request may carry (server rejects >200 with 400). */
const val COMPUTER_NOTIFY_AGENT_IDS_MAX = 200

/** Who created another member's agent; null when no person did (nobody to notify). */
data class ComputerAgentOwner(
    val id: String,
    val name: String,
)

/** One of other people's agents blocking a Computer's delete/revoke (web `OthersAgent`). */
data class ComputerOthersAgent(
    val id: String,
    val name: String,
    /** Owner's display name (else username); null when no person created it. */
    val owner: ComputerAgentOwner?,
    /** When Raft last reminded that owner to move it (ISO timestamp). */
    val ownerNotifiedAt: String?,
    /** When the next reminder is allowed (once per 24h, shared with Delete Account); null = now. */
    val ownerNextNotifyAt: String?,
    /** Personal assistant: only its owner can move it (a server admin can only stop it). */
    val personalAssistant: Boolean,
)

/** Why the server skipped reminding an agent's owner (web `NotifySkipReason`). */
enum class ComputerNotifySkipReason(val wire: String) {
    RateLimited("rate_limited"),
    NotOnYourComputer("not_on_your_computer"),
    NoOwner("no_owner"),
    NotDelivered("not_delivered");

    companion object {
        fun fromWireOrNull(raw: String?): ComputerNotifySkipReason? =
            entries.firstOrNull { it.wire == raw }
    }
}

data class ComputerNotifiedOwnerEntry(
    val agentId: String,
    val notifiedAt: String,
)

data class ComputerSkippedOwnerEntry(
    val agentId: String,
    val reason: ComputerNotifySkipReason,
    val nextAllowedAt: String?,
)

data class ComputerNotifyAgentOwnersResult(
    val notified: List<ComputerNotifiedOwnerEntry> = emptyList(),
    val skipped: List<ComputerSkippedOwnerEntry> = emptyList(),
)

/** Outcome of `POST servers/:id/machines/:machineId/revoke-computer-key` with `delete: true`. */
sealed interface ComputerRevokeAndDeleteResult {
    /** Key revoked and machine removed in the same server transaction. */
    data object Deleted : ComputerRevokeAndDeleteResult

    /** Key is revoked (or already had no live key / legacy server without `delete`), machine still listed. */
    data object Revoked : ComputerRevokeAndDeleteResult

    /** 409 `others_agents_on_computer`: other people's live agents are still on it; nothing changed. */
    data class OthersAgents(val agents: List<ComputerOthersAgent>) : ComputerRevokeAndDeleteResult

    /** Any other refusal or transport error; nothing changed. */
    data class Failed(val error: ScreenError, val httpStatus: Int? = null) : ComputerRevokeAndDeleteResult
}

/** Outcome of `DELETE servers/:id/machines/:machineId`. */
sealed interface ComputerDeleteMachineResult {
    data object Deleted : ComputerDeleteMachineResult

    data class Failed(val error: ScreenError, val httpStatus: Int? = null) : ComputerDeleteMachineResult
}

/** Why fallback `DELETE` refused after a key revoke (web `deleteRefusalReason`). */
enum class ComputerDeleteRefusalReason {
    Agents,
    Migration,
    Profile,
    Forbidden,
    Other,
}

@RaftCoreInternalApi fun computerDeleteRefusalReason(
    httpStatus: Int?,
    serverCode: String?,
): ComputerDeleteRefusalReason {
    if (httpStatus == 403) return ComputerDeleteRefusalReason.Forbidden
    return when (serverCode?.trim()) {
        "MACHINE_HAS_ASSIGNED_AGENTS" -> ComputerDeleteRefusalReason.Agents
        "MACHINE_HAS_ACTIVE_MIGRATION" -> ComputerDeleteRefusalReason.Migration
        "MACHINE_HAS_ACTIVE_RUNTIME_PROFILE" -> ComputerDeleteRefusalReason.Profile
        else -> ComputerDeleteRefusalReason.Other
    }
}

/**
 * Web `isOwnAgent` (`othersAgentsApi.ts`, server `services/computerAgents.ts` `ownedByActor`):
 * the viewer created it (`creatorType === "user" && creatorId === userId`) or it is their personal
 * assistant (`assistantOwnerUserId === userId`). Only these block deleting one's own Computer
 * before hitting the server.
 */
@RaftCoreInternalApi fun isOwnComputerAgent(
    creatorUserId: String?,
    assistantOwnerUserId: String?,
    currentUserId: String?,
): Boolean {
    val me = currentUserId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return creatorUserId?.trim() == me || assistantOwnerUserId?.trim() == me
}

/** Per-dialog state for Notify Owner to Move It (web `AgentOwnerNotifyState`). */
data class ComputerAgentOwnerNotifyState(
    val notifiedAt: Map<String, String> = emptyMap(),
    val nextAllowedAt: Map<String, String?> = emptyMap(),
    val ownerless: Set<String> = emptySet(),
    val busy: Set<String> = emptySet(),
    val failed: Set<String> = emptySet(),
    val undelivered: Set<String> = emptySet(),
)

data class ComputerAgentNextReminder(
    val limited: Boolean,
    val at: String?,
)

/** The agent's owner, unless a notify call learned it has none (web `ownerNow`). */
@RaftCoreInternalApi fun ComputerAgentOwnerNotifyState.ownerNow(agent: ComputerOthersAgent): ComputerAgentOwner? =
    if (agent.id in ownerless) null else agent.owner

/** Latest known notification timestamp for [agent] (web `later(agent.ownerNotifiedAt, state.notifiedAt.get(agent.id))`). */
@RaftCoreInternalApi fun ComputerAgentOwnerNotifyState.latestNotifiedAt(agent: ComputerOthersAgent): String? {
    val a = agent.ownerNotifiedAt
    val b = notifiedAt[agent.id]
    if (a == null) return b
    if (b == null) return a
    val aMs = parseRaftTimestampEpochMillisOrNull(a, 0) ?: return b
    val bMs = parseRaftTimestampEpochMillisOrNull(b, 0) ?: return a
    return if (aMs >= bMs) a else b
}

/**
 * Whether [agent] is still inside the once-per-24-hour reminder limit at [nowEpochMillis]
 * (web `nextReminder`): checks this screen's `rate_limited` answer first (a null time waits until
 * the dialog reopens), then the list's `ownerNextNotifyAt`.
 */
@RaftCoreInternalApi fun ComputerAgentOwnerNotifyState.nextReminder(
    agent: ComputerOthersAgent,
    nowEpochMillis: Long,
): ComputerAgentNextReminder {
    if (agent.id in nextAllowedAt) {
        val at = nextAllowedAt[agent.id]
        val atMs = at?.let { parseRaftTimestampEpochMillisOrNull(it, 0) }
        if (at == null || (atMs != null && atMs > nowEpochMillis)) {
            return ComputerAgentNextReminder(limited = true, at = at)
        }
    }
    val fromList = agent.ownerNextNotifyAt
    val fromListMs = fromList?.let { parseRaftTimestampEpochMillisOrNull(it, 0) }
    if (fromList != null && fromListMs != null && fromListMs > nowEpochMillis) {
        return ComputerAgentNextReminder(limited = true, at = fromList)
    }
    return ComputerAgentNextReminder(limited = false, at = null)
}

/** Whether a reminder may be sent to [agent]'s owner right now (web `canNotifyNow`). */
@RaftCoreInternalApi fun ComputerAgentOwnerNotifyState.canNotifyNow(
    agent: ComputerOthersAgent,
    nowEpochMillis: Long,
): Boolean = ownerNow(agent) != null && !nextReminder(agent, nowEpochMillis).limited && agent.id !in busy

@RaftCoreInternalApi fun ComputerAgentOwnerNotifyState.beginNotify(
    agentIds: Collection<String>,
): ComputerAgentOwnerNotifyState {
    if (agentIds.isEmpty()) return this
    val ids = agentIds.toSet()
    return copy(
        busy = busy + ids,
        failed = failed - ids,
        undelivered = undelivered - ids,
    )
}

@RaftCoreInternalApi fun ComputerAgentOwnerNotifyState.applyNotifyResult(
    agentIds: Collection<String>,
    result: ComputerNotifyAgentOwnersResult,
): ComputerAgentOwnerNotifyState {
    val ids = agentIds.toSet()
    val nextNotified = notifiedAt.toMutableMap()
    val nextAllowed = nextAllowedAt.toMutableMap()
    val nextOwnerless = ownerless.toMutableSet()
    val nextUndelivered = undelivered.toMutableSet()
    for (entry in result.notified) {
        nextNotified[entry.agentId] = entry.notifiedAt
        nextAllowed.remove(entry.agentId)
    }
    for (entry in result.skipped) {
        when (entry.reason) {
            ComputerNotifySkipReason.RateLimited -> nextAllowed[entry.agentId] = entry.nextAllowedAt
            ComputerNotifySkipReason.NoOwner -> nextOwnerless.add(entry.agentId)
            ComputerNotifySkipReason.NotDelivered -> nextUndelivered.add(entry.agentId)
            ComputerNotifySkipReason.NotOnYourComputer -> Unit
        }
    }
    return copy(
        notifiedAt = nextNotified,
        nextAllowedAt = nextAllowed,
        ownerless = nextOwnerless,
        undelivered = nextUndelivered,
        busy = busy - ids,
    )
}

@RaftCoreInternalApi fun ComputerAgentOwnerNotifyState.applyNotifyFailure(
    agentIds: Collection<String>,
): ComputerAgentOwnerNotifyState {
    val ids = agentIds.toSet()
    return copy(
        busy = busy - ids,
        failed = failed + ids,
    )
}

private val othersAgentsJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun validIsoTimestampOrNull(value: String?): String? {
    val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return trimmed.takeIf { parseRaftTimestampEpochMillisOrNull(it, 0) != null }
}

private fun parseOthersAgentOwner(element: Any?): ComputerAgentOwner? {
    val obj = element as? JsonObject ?: return null
    val id = obj.stringOrNull("id")?.takeIf { it.isNotEmpty() } ?: return null
    val name = obj.stringOrNull("name")?.takeIf { it.isNotEmpty() } ?: id
    return ComputerAgentOwner(id = id, name = name)
}

private fun parseOthersAgent(obj: JsonObject): ComputerOthersAgent? {
    val id = obj.stringOrNull("id")?.takeIf { it.isNotEmpty() } ?: return null
    val name = obj.stringOrNull("name") ?: id
    val personalAssistant = (obj["personalAssistant"] as? JsonPrimitive)?.booleanOrNull == true
    return ComputerOthersAgent(
        id = id,
        name = name,
        owner = parseOthersAgentOwner(obj["owner"]),
        ownerNotifiedAt = validIsoTimestampOrNull(obj.stringOrNull("ownerNotifiedAt")),
        ownerNextNotifyAt = validIsoTimestampOrNull(obj.stringOrNull("ownerNextNotifyAt")),
        personalAssistant = personalAssistant,
    )
}

/** Body for `POST servers/:id/machines/:machineId/revoke-computer-key` with `delete: true`. */
@RaftCoreInternalApi fun buildRevokeAndDeleteComputerBody(forceAgentIds: List<String> = emptyList()): String =
    buildJsonObject {
        put("delete", true)
        if (forceAgentIds.isNotEmpty()) {
            put(
                "forceAgentIds",
                buildJsonArray {
                    forceAgentIds.forEach { add(JsonPrimitive(it)) }
                },
            )
        }
    }.toString()

/** Body for `POST servers/:id/machines/:machineId/notify-agent-owners`. */
@RaftCoreInternalApi fun buildNotifyAgentOwnersBody(agentIds: List<String>): String =
    buildJsonObject {
        put(
            "agentIds",
            buildJsonArray {
                agentIds.forEach { add(JsonPrimitive(it)) }
            },
        )
    }.toString()

/** Returns true when a 2xx `revoke-computer-key` response reports `"deleted": true`. */
@RaftCoreInternalApi fun parseRevokeAndDeleteDeletedFlag(bodyText: String?): Boolean {
    val body = bodyText?.takeIf { it.isNotBlank() } ?: return false
    val obj = runCatching { othersAgentsJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return false
    return (obj["deleted"] as? JsonPrimitive)?.booleanOrNull == true
}

/**
 * Parses a 409 `others_agents_on_computer` response body into its non-empty [ComputerOthersAgent]
 * list (web `othersAgentsOf`), or null when the status/code/payload doesn't match.
 */
@RaftCoreInternalApi fun parseOthersAgentsConflictResponse(
    httpStatus: Int,
    bodyText: String?,
): List<ComputerOthersAgent>? {
    if (httpStatus != 409) return null
    val body = bodyText?.takeIf { it.isNotBlank() } ?: return null
    val obj = runCatching { othersAgentsJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    if (obj.stringOrNull("code") != "others_agents_on_computer") return null
    val rawAgents = obj["agents"] as? JsonArray ?: return null
    val agents = rawAgents.mapNotNull { (it as? JsonObject)?.let(::parseOthersAgent) }
    return agents.takeIf { it.isNotEmpty() }
}

/** Parses `POST .../notify-agent-owners` response body (web `notifyResultOf`). */
@RaftCoreInternalApi fun parseNotifyAgentOwnersResponse(bodyText: String?): ComputerNotifyAgentOwnersResult {
    val body = bodyText?.takeIf { it.isNotBlank() } ?: return ComputerNotifyAgentOwnersResult()
    val obj = runCatching { othersAgentsJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
        ?: return ComputerNotifyAgentOwnersResult()
    val notified =
        (obj["notified"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val rec = entry as? JsonObject ?: return@mapNotNull null
            val agentId = rec.stringOrNull("agentId")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val notifiedAt = validIsoTimestampOrNull(rec.stringOrNull("notifiedAt")) ?: return@mapNotNull null
            ComputerNotifiedOwnerEntry(agentId = agentId, notifiedAt = notifiedAt)
        }
    val skipped =
        (obj["skipped"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val rec = entry as? JsonObject ?: return@mapNotNull null
            val agentId = rec.stringOrNull("agentId")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val reason = ComputerNotifySkipReason.fromWireOrNull(rec.stringOrNull("reason")) ?: return@mapNotNull null
            ComputerSkippedOwnerEntry(
                agentId = agentId,
                reason = reason,
                nextAllowedAt = validIsoTimestampOrNull(rec.stringOrNull("nextAllowedAt")),
            )
        }
    return ComputerNotifyAgentOwnersResult(notified = notified, skipped = skipped)
}

interface ComputerOthersAgentsRepository {
    suspend fun revokeKeyAndDeleteComputer(
        serverId: ServerId,
        machineId: String,
        forceAgentIds: List<String> = emptyList(),
    ): ComputerRevokeAndDeleteResult

    suspend fun deleteMachine(
        serverId: ServerId,
        machineId: String,
    ): ComputerDeleteMachineResult

    suspend fun notifyAgentOwners(
        serverId: ServerId,
        machineId: String,
        agentIds: List<String>,
    ): DataResult<ComputerNotifyAgentOwnersResult>
}

class NetworkComputerOthersAgentsRepository(
    private val networkClient: RaftNetworkClient,
) : ComputerOthersAgentsRepository {
    override suspend fun revokeKeyAndDeleteComputer(
        serverId: ServerId,
        machineId: String,
        forceAgentIds: List<String>,
    ): ComputerRevokeAndDeleteResult {
        val cleanMachineId = machineId.trim()
        if (serverId.value.isBlank() || cleanMachineId.isBlank()) {
            return ComputerRevokeAndDeleteResult.Failed(
                ScreenError(code = "computer_delete_unavailable", retryable = false, traceId = null)
            )
        }
        val response =
            runCatchingPreservingCancellation {
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "servers/${serverId.value}/machines/$cleanMachineId/revoke-computer-key",
                    method = "POST",
                    serverIdOverride = serverId,
                    bodyText = buildRevokeAndDeleteComputerBody(forceAgentIds),
                )
            }.getOrElse {
                return ComputerRevokeAndDeleteResult.Failed(
                    ScreenError(code = "computer_delete_network", retryable = true, traceId = null)
                )
            }
        if (response.isSuccessful) {
            return if (parseRevokeAndDeleteDeletedFlag(response.bodyText)) {
                ComputerRevokeAndDeleteResult.Deleted
            } else {
                ComputerRevokeAndDeleteResult.Revoked
            }
        }
        val payload = response.errorBodyText?.takeIf { it.isNotBlank() } ?: response.bodyText
        val envelope = response.parseHttpErrorEnvelope()
        if (response.code == 409 && envelope?.serverCode == "no_live_computer_key") {
            return ComputerRevokeAndDeleteResult.Revoked
        }
        parseOthersAgentsConflictResponse(response.code, payload)?.let { agents ->
            return ComputerRevokeAndDeleteResult.OthersAgents(agents)
        }
        return ComputerRevokeAndDeleteResult.Failed(
            error =
                ScreenError(
                    code = envelope?.serverCode ?: "computer_delete_http_${response.code}",
                    retryable = response.code >= 500 || response.code == 429,
                    serverMessage = envelope?.serverMessage,
                    serverCode = envelope?.serverCode,
                    traceId = response.traceId,
                ),
            httpStatus = response.code.takeIf { it > 0 },
        )
    }

    override suspend fun deleteMachine(
        serverId: ServerId,
        machineId: String,
    ): ComputerDeleteMachineResult {
        val cleanMachineId = machineId.trim()
        if (serverId.value.isBlank() || cleanMachineId.isBlank()) {
            return ComputerDeleteMachineResult.Failed(
                ScreenError(code = "computer_delete_unavailable", retryable = false, traceId = null)
            )
        }
        val response =
            runCatchingPreservingCancellation {
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "servers/${serverId.value}/machines/$cleanMachineId",
                    method = "DELETE",
                    serverIdOverride = serverId,
                )
            }.getOrElse {
                return ComputerDeleteMachineResult.Failed(
                    ScreenError(code = "computer_delete_network", retryable = true, traceId = null)
                )
            }
        if (response.isSuccessful) return ComputerDeleteMachineResult.Deleted
        val envelope = response.parseHttpErrorEnvelope()
        return ComputerDeleteMachineResult.Failed(
            error =
                ScreenError(
                    code = envelope?.serverCode ?: "computer_delete_http_${response.code}",
                    retryable = response.code >= 500 || response.code == 429,
                    serverMessage = envelope?.serverMessage,
                    serverCode = envelope?.serverCode,
                    traceId = response.traceId,
                ),
            httpStatus = response.code.takeIf { it > 0 },
        )
    }

    override suspend fun notifyAgentOwners(
        serverId: ServerId,
        machineId: String,
        agentIds: List<String>,
    ): DataResult<ComputerNotifyAgentOwnersResult> {
        val cleanMachineId = machineId.trim()
        if (serverId.value.isBlank() || cleanMachineId.isBlank()) {
            return DataResult.Error(ScreenError(code = "computer_notify_unavailable", retryable = false, traceId = null))
        }
        val ids = agentIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty()) return DataResult.Content(ComputerNotifyAgentOwnersResult())
        val allNotified = mutableListOf<ComputerNotifiedOwnerEntry>()
        val allSkipped = mutableListOf<ComputerSkippedOwnerEntry>()
        for (batch in ids.chunked(COMPUTER_NOTIFY_AGENT_IDS_MAX)) {
            val response =
                runCatchingPreservingCancellation {
                    networkClient.request(
                        priority = DataRequestPriority.Foreground,
                        path = "servers/${serverId.value}/machines/$cleanMachineId/notify-agent-owners",
                        method = "POST",
                        serverIdOverride = serverId,
                        bodyText = buildNotifyAgentOwnersBody(batch),
                    )
                }.getOrElse {
                    return DataResult.Error(ScreenError(code = "computer_notify_network", retryable = true, traceId = null))
                }
            if (!response.isSuccessful) {
                val envelope = response.parseHttpErrorEnvelope()
                return DataResult.Error(
                    ScreenError(
                        code = envelope?.serverCode ?: "computer_notify_http_${response.code}",
                        retryable = response.code >= 500 || response.code == 429,
                        serverMessage = envelope?.serverMessage,
                        serverCode = envelope?.serverCode,
                        traceId = response.traceId,
                    )
                )
            }
            val parsed = parseNotifyAgentOwnersResponse(response.bodyText)
            allNotified += parsed.notified
            allSkipped += parsed.skipped
        }
        return DataResult.Content(ComputerNotifyAgentOwnersResult(notified = allNotified, skipped = allSkipped))
    }
}
