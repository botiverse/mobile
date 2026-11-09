package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What ending one person's membership of a server takes offline (botiverse/slock #13547,
 * packages/shared/src/memberRemovalImpact.ts `MemberRemovalImpact`), as
 * `GET servers/:id/members/:memberId/removal-impact` answers it for the Remove member and Leave
 * server confirmations. Removing or leaving revokes every live Computer the person attached to the
 * server; the agents on those Computers stay assigned but go offline.
 */
data class MemberRemovalImpact(
    /** Live Computers the person attached to this server. */
    val computers: Int,
    /** Live agents bound to those Computers. */
    val agents: Int,
    /** Of those, the ones that are not the person's own. */
    val othersAgents: Int,
    /** Up to [MEMBER_REMOVAL_IMPACT_MAX_COMPUTER_NAMES] of those Computers' names, by name. */
    val computerNames: List<String>
)

/** Shared `MEMBER_REMOVAL_IMPACT_MAX_COMPUTER_NAMES`. */
const val MEMBER_REMOVAL_IMPACT_MAX_COMPUTER_NAMES = 3

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

/** The confirmation's primary action waits only while the read is in flight (web `confirmDisabled`). */
val MemberRemovalImpactState.holdsConfirm: Boolean get() = this == MemberRemovalImpactState.Loading

interface MemberRemovalImpactRepository {
    /** `GET servers/:serverId/members/:memberId/removal-impact`, given up after [MEMBER_REMOVAL_IMPACT_TIMEOUT_MILLIS]. */
    suspend fun removalImpact(serverId: ServerId, memberId: String): DataResult<MemberRemovalImpact>
}

@Serializable
private data class MemberRemovalImpactDto(
    val computers: Int? = null,
    val agents: Int? = null,
    val othersAgents: Int? = null,
    val computerNames: List<String>? = null
)

private val memberRemovalImpactJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

/** Decodes the route's answer; null when it is not one (no `computers` count). */
internal fun parseMemberRemovalImpact(bodyText: String?): MemberRemovalImpact? {
    val body = bodyText?.takeIf { it.isNotBlank() } ?: return null
    val dto = runCatching { memberRemovalImpactJson.decodeFromString(MemberRemovalImpactDto.serializer(), body) }.getOrNull() ?: return null
    val computers = dto.computers ?: return null
    return MemberRemovalImpact(
        computers = computers.coerceAtLeast(0),
        agents = (dto.agents ?: 0).coerceAtLeast(0),
        othersAgents = (dto.othersAgents ?: 0).coerceAtLeast(0),
        computerNames = dto.computerNames.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.take(MEMBER_REMOVAL_IMPACT_MAX_COMPUTER_NAMES)
    )
}

class NetworkMemberRemovalImpactRepository(
    private val networkClient: RaftNetworkClient,
    private val timeoutMillis: Long = MEMBER_REMOVAL_IMPACT_TIMEOUT_MILLIS,
) : MemberRemovalImpactRepository {
    override suspend fun removalImpact(serverId: ServerId, memberId: String): DataResult<MemberRemovalImpact> {
        val cleanMemberId = memberId.trim()
        if (serverId.value.isBlank() || cleanMemberId.isBlank()) {
            return DataResult.Error(ScreenError(code = "member_removal_impact_unavailable", retryable = false))
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
                ?: return DataResult.Error(ScreenError(code = "member_removal_impact_timeout", retryable = true))
        if (!response.isSuccessful) {
            return DataResult.Error(ScreenError(code = "member_removal_impact_http_${response.code}", retryable = true))
        }
        val impact =
            parseMemberRemovalImpact(response.bodyText)
                ?: return DataResult.Error(ScreenError(code = "member_removal_impact_contract_mismatch", retryable = false))
        return DataResult.Content(impact)
    }
}

/** Web `useMemberRemovalImpact` once the read settles: the answer as the confirmation's state. */
fun DataResult<MemberRemovalImpact>.toMemberRemovalImpactState(): MemberRemovalImpactState =
    when (this) {
        is DataResult.Content -> MemberRemovalImpactState.Ready(data)
        else -> MemberRemovalImpactState.Failed
    }
