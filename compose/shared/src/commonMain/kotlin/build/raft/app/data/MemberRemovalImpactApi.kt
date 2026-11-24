package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What ending one person's membership of a server involves (botiverse/slock #13547, #16386, #16435,
 * packages/shared/src/memberRemovalImpact.ts `MemberRemovalImpact`), as
 * `GET servers/:id/members/:memberId/removal-impact` answers it for the Remove member and Leave
 * server confirmations. While the person is the Attached by of a live Computer here, leaving and
 * being removed are refused (`409 member_has_computer`, the same [attachedComputers] list); an
 * owner or admin may disconnect them with the removal in one request (`{ revokeComputers: true,
 * computerIds }`). Legacy machines the person registered are not blocking: the removal revokes
 * their keys, and their agents stay bound and offline. [handedOverComputers] lists live Computers
 * here the person handed over so the Leave dialog can remind them to sign out of Claude/Codex there.
 */
data class MemberRemovalImpact(
    /** Live Computers the person attached to this server, and legacy machines they registered here. */
    val computers: Int,
    /** Live agents bound to those Computers' machines. */
    val agents: Int,
    /** Of those, the ones that are not the person's own. */
    val othersAgents: Int,
    /** Up to [MEMBER_REMOVAL_IMPACT_MAX_COMPUTER_NAMES] of those Computers' names, by name. */
    val computerNames: List<String>,
    /** Leaving or removing is refused now: [attachedComputers] is not empty. */
    val blocking: Boolean = false,
    /** Every live Computer the person is the Attached by of here, by name (not the legacy machines). */
    val attachedComputers: List<MemberAttachedComputer> = emptyList(),
    /** Live Computers here the person handed over to someone else (not blocking; absent on older servers). */
    val handedOverComputers: List<MemberHandedOverComputer> = emptyList(),
)

/**
 * A live Computer the departing person is the Attached by of, as the 409 `member_has_computer`
 * refusal and the removal impact list it (shared `MemberAttachedComputer`).
 */
data class MemberAttachedComputer(
    /** The `computers` row: what an owner or admin names in `computerIds` to disconnect it with the removal. */
    val id: String,
    /** Its machine, for the Computer page link; null when it has none. */
    val machineId: String? = null,
    val name: String,
    /** Live agents bound to its machine. */
    val agentCount: Int = 0,
    /** Of those, the ones that are not the person's own (as `othersAgents`). */
    val othersAgentCount: Int = 0,
)

/** A live Computer the person handed over (shared `MemberHandedOverComputer`). */
data class MemberHandedOverComputer(
    val id: String,
    val machineId: String? = null,
    val name: String,
)

/** Shared `MEMBER_REMOVAL_IMPACT_MAX_COMPUTER_NAMES`. */
const val MEMBER_REMOVAL_IMPACT_MAX_COMPUTER_NAMES = 3

/** Shared `REFUSAL_CODES.member_has_computer`: leave or remove refused because live Computers are attached. */
const val MEMBER_HAS_COMPUTER_REFUSAL_CODE = "member_has_computer"

/** Web `IMPACT_READ_TIMEOUT_MS`: a read that hangs must not hold the confirm back. */
const val MEMBER_REMOVAL_IMPACT_TIMEOUT_MILLIS = 10_000L

/** Web `MemberRemovalImpactState`: what the confirmation shows about the Computers. */
sealed interface MemberRemovalImpactState {
    /** Nothing to read (a Guest leaving attaches no Computers). */
    data object None : MemberRemovalImpactState

    /** The read is in flight: the confirm waits ("Checking…"). */
    data object Loading : MemberRemovalImpactState

    data class Ready(val impact: MemberRemovalImpact) : MemberRemovalImpactState

    /** The read failed or timed out: says so, and the confirm stays available. */
    data object Failed : MemberRemovalImpactState
}

/**
 * Web `removalBlockedByComputers`: leaving or removing is refused while the person is the Attached
 * by of a live Computer here (409 `member_has_computer`): those Computers.
 */
fun MemberRemovalImpactState.blockingComputers(): List<MemberAttachedComputer> =
    if (this is MemberRemovalImpactState.Ready && impact.blocking) impact.attachedComputers else emptyList()

/** Live Computers here the person handed over to someone else (not blocking; Leave server sign-in note). */
fun MemberRemovalImpactState.handedOverComputers(): List<MemberHandedOverComputer> =
    if (this is MemberRemovalImpactState.Ready) impact.handedOverComputers else emptyList()

/** Remove member's primary action waits only while the read is in flight (web Remove confirmDisabled). */
val MemberRemovalImpactState.holdsConfirm: Boolean get() = this == MemberRemovalImpactState.Loading

/** Leave server's confirm waits while the read is in flight OR while blocking Computers are attached (web Leave confirmDisabled). */
val MemberRemovalImpactState.holdsLeaveConfirm: Boolean
    get() = this == MemberRemovalImpactState.Loading || blockingComputers().isNotEmpty()

/** Whether [this] server refusal code is `member_has_computer` (web `refreshesOnMemberHasComputer`). */
fun String?.isMemberHasComputerRefusalCode(): Boolean =
    this?.trim().equals(MEMBER_HAS_COMPUTER_REFUSAL_CODE, ignoreCase = true)

/** Whether [this] leave/remove result failed with `409 member_has_computer` and should re-read removal impact. */
fun DataResult<*>.isMemberHasComputerRefusal(): Boolean =
    (this as? DataResult.Error)?.error?.serverCode.isMemberHasComputerRefusalCode()

interface MemberRemovalImpactRepository {
    /** `GET servers/:serverId/members/:memberId/removal-impact`, given up after [MEMBER_REMOVAL_IMPACT_TIMEOUT_MILLIS]. */
    suspend fun removalImpact(serverId: ServerId, memberId: String): DataResult<MemberRemovalImpact>
}

@Serializable
private data class MemberAttachedComputerDto(
    val id: String? = null,
    val machineId: String? = null,
    val name: String? = null,
    val agentCount: Int? = null,
    val othersAgentCount: Int? = null,
)

@Serializable
private data class MemberHandedOverComputerDto(
    val id: String? = null,
    val machineId: String? = null,
    val name: String? = null,
)

@Serializable
private data class MemberRemovalImpactDto(
    val computers: Int? = null,
    val agents: Int? = null,
    val othersAgents: Int? = null,
    val computerNames: List<String>? = null,
    val blocking: Boolean? = null,
    val attachedComputers: List<MemberAttachedComputerDto>? = null,
    val handedOverComputers: List<MemberHandedOverComputerDto>? = null,
)

private val memberRemovalImpactJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

/** Decodes the route's answer; null when it is not one (no `computers` count). */
internal fun parseMemberRemovalImpact(bodyText: String?): MemberRemovalImpact? {
    val body = bodyText?.takeIf { it.isNotBlank() } ?: return null
    val dto = runCatching { memberRemovalImpactJson.decodeFromString(MemberRemovalImpactDto.serializer(), body) }.getOrNull() ?: return null
    val computers = dto.computers ?: return null
    val attachedComputers =
        dto.attachedComputers.orEmpty().mapNotNull { entry ->
            val id = entry.id?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val name = entry.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            MemberAttachedComputer(
                id = id,
                machineId = entry.machineId?.trim()?.takeIf { it.isNotEmpty() },
                name = name,
                agentCount = (entry.agentCount ?: 0).coerceAtLeast(0),
                othersAgentCount = (entry.othersAgentCount ?: 0).coerceAtLeast(0),
            )
        }
    val handedOverComputers =
        dto.handedOverComputers.orEmpty().mapNotNull { entry ->
            val id = entry.id?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val name = entry.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            MemberHandedOverComputer(
                id = id,
                machineId = entry.machineId?.trim()?.takeIf { it.isNotEmpty() },
                name = name,
            )
        }
    return MemberRemovalImpact(
        computers = computers.coerceAtLeast(0),
        agents = (dto.agents ?: 0).coerceAtLeast(0),
        othersAgents = (dto.othersAgents ?: 0).coerceAtLeast(0),
        computerNames = dto.computerNames.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.take(MEMBER_REMOVAL_IMPACT_MAX_COMPUTER_NAMES),
        blocking = dto.blocking == true,
        attachedComputers = attachedComputers,
        handedOverComputers = handedOverComputers,
    )
}

class NetworkMemberRemovalImpactRepository(
    private val networkClient: RaftNetworkClient,
    private val timeoutMillis: Long = MEMBER_REMOVAL_IMPACT_TIMEOUT_MILLIS,
) : MemberRemovalImpactRepository {
    override suspend fun removalImpact(serverId: ServerId, memberId: String): DataResult<MemberRemovalImpact> {
        val cleanMemberId = memberId.trim()
        if (serverId.value.isBlank() || cleanMemberId.isBlank()) {
            return DataResult.Error(ScreenError(code = "member_removal_impact_unavailable", retryable = false, traceId = null))
        }
        val response =
            runCatchingPreservingCancellation {
                withTimeoutOrNull(timeoutMillis) {
                    networkClient.request(
                        priority = DataRequestPriority.Foreground,
                        path = "servers/${serverId.value}/members/$cleanMemberId/removal-impact",
                        serverIdOverride = serverId,
                    )
                }
            }.getOrNull()
                ?: return DataResult.Error(ScreenError(code = "member_removal_impact_timeout", retryable = true, traceId = null))
        if (!response.isSuccessful) {
            val envelope = response.parseHttpErrorEnvelope()
            return DataResult.Error(
                ScreenError(
                    code = "member_removal_impact_http_${response.code}",
                    retryable = true,
                    serverMessage = envelope?.serverMessage,
                    serverCode = envelope?.serverCode,
                    traceId = response.traceId,
                )
            )
        }
        val impact =
            parseMemberRemovalImpact(response.bodyText)
                ?: return DataResult.Error(ScreenError(code = "member_removal_impact_contract_mismatch", retryable = false, traceId = response.traceId))
        return DataResult.Content(impact)
    }
}

/** Web `useMemberRemovalImpact` once the read settles: the answer as the confirmation's state. */
fun DataResult<MemberRemovalImpact>.toMemberRemovalImpactState(): MemberRemovalImpactState =
    when (this) {
        is DataResult.Content -> MemberRemovalImpactState.Ready(data)
        else -> MemberRemovalImpactState.Failed
    }
