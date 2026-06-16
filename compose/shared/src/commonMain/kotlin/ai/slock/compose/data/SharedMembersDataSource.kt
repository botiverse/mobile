package ai.slock.compose.data

import ai.slock.compose.SharedCreateAgentDialogSubmit
import ai.slock.compose.SharedCreateAgentMachineOption
import ai.slock.compose.model.MemberKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * KMP-side data source for the Members page flows.
 *
 * Mirrors the existing `SharedThreadDataSource` pattern: thin wrapper that
 * takes typed API clients and exposes a focused surface. The host page builds
 * the underlying transport (e.g. `KuiklySlockNetworkClient` from the pager's
 * `SlockNetworkModule`) and passes both API clients in.
 *
 * Capability surface (task #42):
 *   - `createAgent`: POST /agents with the runtime config payload from
 *     `SharedCreateAgentDialog`. Returns the new `MemberProfile` so the caller
 *     can append it to the Members list without re-fetching.
 *   - `loadMachines`: GET /servers/{id}/machines for the computer picker.
 *   - `loadServerMembers`: GET /servers/{id}/members for the add-human
 *     picker (candidates minus current channel members).
 *   - `addChannelMember`: POST /channels/{id}/members with the kind-keyed
 *     body (`{agentId}` or `{userId}`) for the humans section "+".
 */
internal class SharedMembersDataSource(
    private val agentsApiClient: SlockAgentsApiClient,
    private val computersApiClient: SlockComputersApiClient,
    private val memberRepository: MemberRepository
) {
    suspend fun createAgent(
        submit: SharedCreateAgentDialogSubmit
    ): DataResult<ai.slock.compose.model.MemberProfile> {
        val body = buildCreateAgentBody(
            CreateAgentBody(
                name = submit.name,
                description = submit.description,
                model = submit.runtimeConfig?.model?.let { it.toModelIdOrNull() },
                runtime = submit.runtimeConfig?.runtime,
                reasoningEffort = submit.runtimeConfig?.reasoningEffort,
                machineId = submit.machineId,
                envVars = submit.runtimeConfig?.envVars,
                runtimeConfig = submit.runtimeConfig,
                external = submit.external.takeIf { it }
            )
        )
        return when (val result = agentsApiClient.createAgent(body)) {
            is DataResult.Content -> {
                val summary = result.data
                val profile = ai.slock.compose.model.MemberProfile(
                    id = ai.slock.compose.model.MemberId(summary.id),
                    displayName = summary.displayName?.takeIf { it.isNotBlank() } ?: summary.name,
                    handle = summary.name,
                    kind = ai.slock.compose.model.MemberKind.Agent,
                    description = summary.activityDetail,
                    roleDescription = summary.model,
                    presence = ai.slock.compose.model.Presence.Offline,
                    agentStatus = ai.slock.compose.model.AgentStatus.Online
                )
                DataResult.Content(profile)
            }
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun loadMachines(
        serverId: String
    ): DataResult<List<SharedCreateAgentMachineOption>> {
        return when (val result = computersApiClient.machines(ai.slock.compose.model.ServerId(serverId))) {
            is DataResult.Content -> {
                val opts = result.data.machines.map { m ->
                    SharedCreateAgentMachineOption(
                        id = m.id,
                        name = m.name,
                        status = when (m.status) {
                            ComputerStatus.Online -> "online"
                            ComputerStatus.Offline -> "offline"
                            ComputerStatus.Unknown -> "unknown"
                        },
                        runtimes = m.runtimes
                    )
                }
                DataResult.Content(opts)
            }
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun loadServerMembers(
        serverId: String
    ): DataResult<List<ai.slock.compose.model.MemberProfile>> {
        return when (val result = memberRepository.getMembers(DataRequestPriority.Foreground, ai.slock.compose.model.ServerId(serverId))) {
            is DataResult.Content -> DataResult.Content(result.data.members)
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun addChannelMember(
        channelId: String,
        memberId: String,
        kind: MemberKind
    ): DataResult<Unit> {
        return when (val result = memberRepository.addChannelMember(
            DataRequestPriority.Foreground,
            AddChannelMemberRequest(
                channelId = ai.slock.compose.model.ChannelId(channelId),
                memberId = ai.slock.compose.model.MemberId(memberId),
                kind = kind
            )
        )) {
            is DataResult.Content -> DataResult.Content(Unit)
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }
}

private fun ai.slock.compose.model.RuntimeModelConfig.toModelIdOrNull(): String? =
    when (this) {
        is ai.slock.compose.model.RuntimeModelConfig.Preset -> id
        is ai.slock.compose.model.RuntimeModelConfig.Custom -> name
    }

