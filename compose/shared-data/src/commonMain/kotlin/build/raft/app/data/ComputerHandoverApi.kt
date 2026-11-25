package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.raftCompareComputerVersions
import build.raft.app.raftIsComputerSemver
import build.raft.app.model.ServerId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Hand over a Computer (botiverse/slock #16435 / #16445 / #16452 / #16619,
 * `packages/shared/src/computerHandover.ts` and `packages/server/src/routes/computerHandoverRoutes.ts`):
 * - `GET    servers/:id/machines/:machineId/handover` → [ComputerHandoverState]
 * - `POST   servers/:id/machines/:machineId/handover` `{ toUserId }` → 201 [ComputerHandoverState]
 * - `DELETE servers/:id/machines/:machineId/handover` → [ComputerHandoverState]
 *
 * The lowest Computer version a handover is offered to and accepted for is `1.25.16` (slock #16619:
 * `1.25.15`'s `handover accept` never printed the approval code). Prereleases of `1.25.16`
 * (`1.25.16-staging.N`, `1.25.16-rc.N`, `1.25.16-alpha.N`) count too: compare against
 * [COMPUTER_HANDOVER_MIN_COMPUTER_VERSION_COMPARED] (`1.25.16-0`).
 */
const val COMPUTER_HANDOVER_MIN_COMPUTER_VERSION = "1.25.16"

/**
 * The handover floor as compared (shared `COMPUTER_HANDOVER_MIN_COMPUTER_VERSION_COMPARED`,
 * slock #16619): the lowest prerelease of [COMPUTER_HANDOVER_MIN_COMPUTER_VERSION], so staging,
 * alpha, and rc builds of that version pass SemVer comparison (`1.25.16-x` sorts below `1.25.16`).
 */
const val COMPUTER_HANDOVER_MIN_COMPUTER_VERSION_COMPARED = "$COMPUTER_HANDOVER_MIN_COMPUTER_VERSION-0"

/**
 * Whether [computerVersion] satisfies the handover floor (slock #16619
 * `computerVersionAtLeast(computerVersion, COMPUTER_HANDOVER_MIN_COMPUTER_VERSION_COMPARED)`):
 * `1.25.15`, unknown, and non-SemVer strings return false; `1.25.16` and its prereleases return true.
 */
@RaftCoreInternalApi fun isComputerHandoverVersionSupported(
    computerVersion: String?,
    minimumVersionCompared: String = COMPUTER_HANDOVER_MIN_COMPUTER_VERSION_COMPARED,
): Boolean {
    val version = computerVersion?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    if (!raftIsComputerSemver(version) || !raftIsComputerSemver(minimumVersionCompared)) return false
    return raftCompareComputerVersions(version, minimumVersionCompared) >= 0
}

/** A person in a handover, as the Computer page names them (shared `ComputerHandoverPerson`). */
data class ComputerHandoverPerson(
    val id: String,
    val name: String,
    val displayName: String? = null,
) {
    /** Display name when present, else handle (web `pending.to.displayName || pending.to.name`). */
    val label: String
        get() = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: name
}

/** The pending (live: open and unexpired) handover of a Computer (shared `ComputerHandoverView`). */
data class ComputerHandoverView(
    val id: String,
    val to: ComputerHandoverPerson,
    val fromUserId: String,
    val createdBy: String,
    val createdAt: String,
    val expiresAt: String,
)

/** What the Computer page shows and offers about handing the Computer over (shared `ComputerHandoverState`). */
data class ComputerHandoverState(
    val handover: ComputerHandoverView? = null,
    /** The viewer may designate (the person who attached it, or a role that may revoke its key). */
    val canDesignate: Boolean = false,
    /** The viewer may cancel the pending one (also its designator, and the designee to decline). */
    val canCancel: Boolean = false,
    /** The Computer reports a version below [COMPUTER_HANDOVER_MIN_COMPUTER_VERSION], or none. */
    val upgradeRequired: Boolean = false,
    val minimumComputerVersion: String = COMPUTER_HANDOVER_MIN_COMPUTER_VERSION,
)

interface ComputerHandoverRepository {
    suspend fun getHandover(serverId: ServerId, machineId: String): DataResult<ComputerHandoverState>

    suspend fun designateHandover(serverId: ServerId, machineId: String, toUserId: String): DataResult<ComputerHandoverState>

    suspend fun cancelHandover(serverId: ServerId, machineId: String): DataResult<ComputerHandoverState>
}

@Serializable
private data class ComputerHandoverPersonDto(
    val id: String? = null,
    val name: String? = null,
    val displayName: String? = null,
)

@Serializable
private data class ComputerHandoverViewDto(
    val id: String? = null,
    val to: ComputerHandoverPersonDto? = null,
    val fromUserId: String? = null,
    val createdBy: String? = null,
    val createdAt: String? = null,
    val expiresAt: String? = null,
)

@Serializable
private data class ComputerHandoverStateDto(
    val handover: ComputerHandoverViewDto? = null,
    val canDesignate: Boolean? = null,
    val canCancel: Boolean? = null,
    val upgradeRequired: Boolean? = null,
    val minimumComputerVersion: String? = null,
)

private val computerHandoverJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

/** Body for `POST servers/:id/machines/:machineId/handover`: `{ toUserId }`. */
@RaftCoreInternalApi fun buildComputerHandoverDesignateBody(toUserId: String): String =
    buildJsonObject { put("toUserId", toUserId.trim()) }.toString()

/** Decodes `ComputerHandoverState`; returns null for blank, malformed, or contract-mismatched bodies. */
@RaftCoreInternalApi fun parseComputerHandoverState(bodyText: String?): ComputerHandoverState? {
    val body = bodyText?.takeIf { it.isNotBlank() } ?: return null
    val dto = runCatching { computerHandoverJson.decodeFromString(ComputerHandoverStateDto.serializer(), body) }.getOrNull() ?: return null
    val canDesignate = dto.canDesignate ?: return null
    val canCancel = dto.canCancel ?: return null
    val upgradeRequired = dto.upgradeRequired ?: return null
    val handover =
        dto.handover?.let { pending ->
            val id = pending.id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val toId = pending.to?.id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val toName = pending.to.name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val fromUserId = pending.fromUserId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val createdBy = pending.createdBy?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val createdAt = pending.createdAt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val expiresAt = pending.expiresAt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            ComputerHandoverView(
                id = id,
                to = ComputerHandoverPerson(
                    id = toId,
                    name = toName,
                    displayName = pending.to.displayName?.trim()?.takeIf { it.isNotEmpty() },
                ),
                fromUserId = fromUserId,
                createdBy = createdBy,
                createdAt = createdAt,
                expiresAt = expiresAt,
            )
        }
    return ComputerHandoverState(
        handover = handover,
        canDesignate = canDesignate,
        canCancel = canCancel,
        upgradeRequired = upgradeRequired,
        minimumComputerVersion = dto.minimumComputerVersion?.trim()?.takeIf { it.isNotEmpty() } ?: COMPUTER_HANDOVER_MIN_COMPUTER_VERSION,
    )
}

class NetworkComputerHandoverRepository(
    private val networkClient: RaftNetworkClient,
) : ComputerHandoverRepository {
    override suspend fun getHandover(serverId: ServerId, machineId: String): DataResult<ComputerHandoverState> =
        request(serverId, machineId, method = "GET", priority = DataRequestPriority.Visible, bodyText = null)

    override suspend fun designateHandover(
        serverId: ServerId,
        machineId: String,
        toUserId: String,
    ): DataResult<ComputerHandoverState> {
        val cleanTarget = toUserId.trim()
        if (cleanTarget.isEmpty()) {
            return DataResult.Error(ScreenError(code = "handover_invalid", retryable = false, traceId = null))
        }
        return request(
            serverId = serverId,
            machineId = machineId,
            method = "POST",
            priority = DataRequestPriority.Foreground,
            bodyText = buildComputerHandoverDesignateBody(cleanTarget),
        )
    }

    override suspend fun cancelHandover(serverId: ServerId, machineId: String): DataResult<ComputerHandoverState> =
        request(serverId, machineId, method = "DELETE", priority = DataRequestPriority.Foreground, bodyText = null)

    private suspend fun request(
        serverId: ServerId,
        machineId: String,
        method: String,
        priority: DataRequestPriority,
        bodyText: String?,
    ): DataResult<ComputerHandoverState> {
        val cleanMachineId = machineId.trim()
        if (serverId.value.isBlank() || cleanMachineId.isBlank()) {
            return DataResult.Error(ScreenError(code = "computer_handover_unavailable", retryable = false, traceId = null))
        }
        val response =
            runCatchingPreservingCancellation {
                networkClient.request(
                    priority = priority,
                    path = "servers/${serverId.value}/machines/$cleanMachineId/handover",
                    method = method,
                    serverIdOverride = serverId,
                    bodyText = bodyText,
                )
            }.getOrElse {
                return DataResult.Error(ScreenError(code = "computer_handover_network", retryable = true, traceId = null))
            }
        if (!response.isSuccessful) {
            val envelope = response.parseHttpErrorEnvelope()
            return DataResult.Error(
                ScreenError(
                    code = envelope?.serverCode ?: "computer_handover_http_${response.code}",
                    retryable = response.code >= 500 || response.code == 429,
                    serverMessage = envelope?.serverMessage,
                    serverCode = envelope?.serverCode,
                    traceId = response.traceId,
                )
            )
        }
        val state =
            parseComputerHandoverState(response.bodyText)
                ?: return DataResult.Error(
                    ScreenError(code = "computer_handover_contract_mismatch", retryable = false, traceId = response.traceId)
                )
        return DataResult.Content(state)
    }
}
