package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.ServerId
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Raft Agent Voice calls (`#wg-mobile-sync:#95`).
// Wire contract verified against botiverse/slock:
//  - packages/shared/src/agentVoiceCall.ts
//  - packages/web/src/features/agent/agentVoiceCallClient.ts
//  - packages/web/src/features/agent/agentVoiceCallEntry.ts
//  - packages/server/src/routes/agents.ts (POST /:id/voice/calls, /:callId/token, /:callId/hang-up)

/** Longest call duration enforced by the voice session service, in minutes. */
@RaftCoreInternalApi
const val AGENT_VOICE_CALL_MAX_MINUTES: Int = 15

private val AGENT_VOICE_CALL_ID_REGEX =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

private val agentVoiceCallJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/** Validates a server-minted call UUID (mirrors `asAgentVoiceCallId` in `@botiverse/raft-shared`). */
@RaftCoreInternalApi
fun asAgentVoiceCallIdOrNull(value: String?): String? =
    value?.takeIf { AGENT_VOICE_CALL_ID_REGEX.matches(it) }

/** Typed reason a voice call could not start or could not connect (mirrors web `AgentVoiceCallProblem`). */
@RaftCoreInternalApi
enum class AgentVoiceCallProblem(val recoverable: Boolean) {
    NotAllowed(recoverable = false),
    AgentOffline(recoverable = true),
    Unavailable(recoverable = true),
    UnsupportedRuntime(recoverable = false),
    RateLimited(recoverable = true),
    Failed(recoverable = true),
    MicPermission(recoverable = true),
    MicBusy(recoverable = true),
    MicNone(recoverable = true),
}

/** Room credentials granted by `POST /agents/:agentId/voice/calls` or `…/:callId/token`. */
@RaftCoreInternalApi
data class AgentVoiceCallRoomGrant(
    val url: String,
    val token: String,
    val expiresAtIso: String,
    val expiresAtEpochMs: Long,
    val relay: String? = null,
)

/** Parsed successful start or token-renewal response. */
@RaftCoreInternalApi
data class AgentVoiceCallStartResult(
    val callId: String,
    val room: AgentVoiceCallRoomGrant,
)

/** Result of `POST /agents/:agentId/voice/calls`. */
@RaftCoreInternalApi
sealed interface AgentVoiceCallStartOutcome {
    data class Started(val result: AgentVoiceCallStartResult) : AgentVoiceCallStartOutcome

    data class Refused(
        val problem: AgentVoiceCallProblem,
        val traceId: String? = null,
    ) : AgentVoiceCallStartOutcome
}

@Serializable
private data class AgentVoiceCallRoomGrantDto(
    val url: String? = null,
    val token: String? = null,
    val expiresAt: String? = null,
    val relay: String? = null,
)

@Serializable
private data class AgentVoiceCallStartResponseDto(
    val callId: String? = null,
    val room: AgentVoiceCallRoomGrantDto? = null,
)

@Serializable
private data class AgentVoiceCallErrorDto(
    val code: String? = null,
    val errorCode: String? = null,
)

/** Maps a non-2xx start response to [AgentVoiceCallProblem], mirroring web `startProblemOf`. */
@RaftCoreInternalApi
fun startProblemOf(statusCode: Int, errorBodyText: String?): AgentVoiceCallProblem {
    if (statusCode == 404) return AgentVoiceCallProblem.NotAllowed
    if (statusCode == 429) return AgentVoiceCallProblem.RateLimited
    if (statusCode == 409) {
        val code =
            errorBodyText
                ?.takeIf { it.isNotBlank() }
                ?.let { raw ->
                    runCatching {
                        val dto = agentVoiceCallJson.decodeFromString(AgentVoiceCallErrorDto.serializer(), raw)
                        looseRefusalCode(code = dto.code, errorCode = dto.errorCode)
                    }.getOrNull()
                }
        return when (code) {
            "voice_call_unavailable" -> AgentVoiceCallProblem.Unavailable
            "agent_offline" -> AgentVoiceCallProblem.AgentOffline
            "unsupported_runtime" -> AgentVoiceCallProblem.UnsupportedRuntime
            else -> AgentVoiceCallProblem.Failed
        }
    }
    return AgentVoiceCallProblem.Failed
}

/** Parses `AgentVoiceCallStarted` strictly at the boundary (mirrors web `readCallAnswer`). */
@RaftCoreInternalApi
fun parseAgentVoiceCallStartResult(bodyText: String): AgentVoiceCallStartResult? =
    runCatching {
        val dto = agentVoiceCallJson.decodeFromString(AgentVoiceCallStartResponseDto.serializer(), bodyText)
        val callId = asAgentVoiceCallIdOrNull(dto.callId) ?: return null
        val roomDto = dto.room ?: return null
        val url = roomDto.url?.takeIf { it.isNotEmpty() } ?: return null
        val token = roomDto.token?.takeIf { it.isNotEmpty() } ?: return null
        val expiresAtIso = roomDto.expiresAt?.takeIf { it.isNotEmpty() } ?: return null
        val expiresAtEpochMs = runCatching { Instant.parse(expiresAtIso).toEpochMilliseconds() }.getOrNull() ?: return null
        AgentVoiceCallStartResult(
            callId = callId,
            room =
                AgentVoiceCallRoomGrant(
                    url = url,
                    token = token,
                    expiresAtIso = expiresAtIso,
                    expiresAtEpochMs = expiresAtEpochMs,
                    relay = roomDto.relay?.takeIf { it.isNotBlank() },
                ),
        )
    }.getOrNull()

/**
 * Pure entry-point predicate mirroring web `agentVoiceCallEntry.ts`:
 * - `agent_voice_v1` flag enabled on the current server
 * - native LiveKit engine registered & supported
 * - server role is known and not `"guest"`
 * - agent is present, not deleted, not an App-managed bot, not stopped by a person
 * - agent has a Computer (`machineId != null`) and that Computer is not known to be offline
 */
@RaftCoreInternalApi
fun agentVoiceCallEntryEligibility(
    featureEnabled: Boolean,
    serverId: String?,
    serverRole: String?,
    agentId: String?,
    deletedAt: String?,
    managedByAppInstallId: String?,
    agentStatus: String?,
    machineId: String?,
    computerOffline: Boolean,
    engineSupported: Boolean = RaftAgentVoiceCallEngineRegistry.isSupported(),
): Boolean {
    if (!featureEnabled) return false
    if (!engineSupported) return false
    if (serverId.isNullOrBlank()) return false
    val role = serverRole?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    if (role.equals("guest", ignoreCase = true)) return false
    if (agentId.isNullOrBlank()) return false
    if (!deletedAt.isNullOrBlank()) return false
    if (!managedByAppInstallId.isNullOrBlank()) return false
    if (agentStatus?.trim()?.equals("stopped", ignoreCase = true) == true) return false
    if (machineId.isNullOrBlank()) return false
    if (computerOffline) return false
    return true
}

/** Network contract for starting, renewing, and hanging up an agent voice call. */
@RaftCoreInternalApi
interface AgentVoiceCallApi {
    suspend fun startCall(agentId: String, serverId: String): AgentVoiceCallStartOutcome

    suspend fun renewToken(agentId: String, callId: String, serverId: String): AgentVoiceCallStartResult?

    suspend fun hangUp(agentId: String, callId: String, serverId: String): Boolean
}

/**
 * Shared network implementation for voice calls. Every request passes `serverIdOverride = ServerId(serverId)`
 * so `X-Server-Id` stays bound to the call's server even if the user switches servers mid-call.
 */
@RaftCoreInternalApi
class RaftAgentVoiceCallNetworkApi(
    private val networkClient: RaftNetworkClient,
) : AgentVoiceCallApi {
    override suspend fun startCall(agentId: String, serverId: String): AgentVoiceCallStartOutcome =
        try {
            val response =
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "agents/${agentId.percentEncode()}/voice/calls",
                    method = "POST",
                    serverIdOverride = ServerId(serverId),
                    bodyText = "{}",
                )
            if (!response.isSuccessful) {
                AgentVoiceCallStartOutcome.Refused(
                    problem = startProblemOf(response.code, response.errorBodyText ?: response.bodyText),
                    traceId = response.traceId,
                )
            } else {
                parseAgentVoiceCallStartResult(response.bodyText.orEmpty())
                    ?.let { AgentVoiceCallStartOutcome.Started(it) }
                    ?: AgentVoiceCallStartOutcome.Refused(
                        problem = AgentVoiceCallProblem.Failed,
                        traceId = response.traceId,
                    )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            AgentVoiceCallStartOutcome.Refused(problem = AgentVoiceCallProblem.Failed)
        }

    override suspend fun renewToken(agentId: String, callId: String, serverId: String): AgentVoiceCallStartResult? =
        try {
            val response =
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "agents/${agentId.percentEncode()}/voice/calls/${callId.percentEncode()}/token",
                    method = "POST",
                    serverIdOverride = ServerId(serverId),
                    bodyText = "{}",
                )
            if (!response.isSuccessful) {
                null
            } else {
                parseAgentVoiceCallStartResult(response.bodyText.orEmpty())
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }

    override suspend fun hangUp(agentId: String, callId: String, serverId: String): Boolean =
        try {
            val response =
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "agents/${agentId.percentEncode()}/voice/calls/${callId.percentEncode()}/hang-up",
                    method = "POST",
                    serverIdOverride = ServerId(serverId),
                    bodyText = "{}",
                )
            response.isSuccessful
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }
}
