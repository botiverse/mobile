package ai.slock.compose.data

import ai.slock.compose.SharedCreateAgentMachineOption
import ai.slock.compose.model.RuntimeConfig
import ai.slock.compose.model.RuntimeModelConfig

/**
 * KMP-side coordinator for the create-agent flow shared between the Members
 * page and the Thread action-card surface.
 *
 * Per @artin 2026-06-17 #members专修 msg=f1db7897: "应该抽象一个统一的". The
 * previous shape had:
 *   - `SharedMembersDataSource.createAgent` for the Members page agents "+"
 *   - `SharedThreadStore.commitAgentCreateActionCardInternal` for Thread
 *     action cards
 * Both built the same `CreateAgentBody` and called `agentsApi.createAgent`
 * with the same shape. This coordinator is the single point that owns the
 * create-agent body construction + API call + result mapping; both surfaces
 * now go through it.
 *
 * Input shape (`CreateAgentRequest`) is a data-layer-defined struct, NOT
 * the UI `SharedCreateAgentDialogSubmit`. Callers convert the dialog submit
 * to a `CreateAgentRequest` at the UI/store boundary; this keeps the data
 * layer free of UI types.
 *
 * The coordinator is `internal` because it is a pure KMP implementation
 * detail. The host (native) does not wire this directly — Composable code
 * builds it via `SharedCreateAgentCoordinator(agentsApiClient, computersApiClient)`.
 */
internal data class CreateAgentRequest(
    val name: String,
    val description: String?,
    val machineId: String?,
    val runtimeConfig: RuntimeConfig?,
    val external: Boolean
)

internal class SharedCreateAgentCoordinator(
    private val agentsApiClient: SlockAgentsApiClient,
    private val computersApiClient: SlockComputersApiClient,
    private val logger: (String) -> Unit = {}
) {
    suspend fun createAgent(request: CreateAgentRequest): DataResult<AgentSummary> {
        val body = buildCreateAgentBody(
            CreateAgentBody(
                name = request.name,
                description = request.description,
                model = request.runtimeConfig?.model?.toModelIdOrNull(),
                runtime = request.runtimeConfig?.runtime,
                reasoningEffort = request.runtimeConfig?.reasoningEffort,
                machineId = request.machineId,
                envVars = request.runtimeConfig?.envVars,
                runtimeConfig = request.runtimeConfig,
                external = request.external.takeIf { it }
            )
        )
        return when (val result = agentsApiClient.createAgent(body)) {
            is DataResult.Content -> DataResult.Content(result.data)
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> {
                logger("create_agent_failed code=${result.error.code}")
                result
            }
            is DataResult.NoPermission -> {
                logger("create_agent_no_permission")
                result
            }
        }
    }

    suspend fun loadMachines(serverId: String): DataResult<List<SharedCreateAgentMachineOption>> {
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
            is DataResult.Error -> {
                logger("load_machines_failed code=${result.error.code}")
                result
            }
            is DataResult.NoPermission -> {
                logger("load_machines_no_permission")
                result
            }
        }
    }
}

private fun RuntimeModelConfig.toModelIdOrNull(): String? =
    when (this) {
        is RuntimeModelConfig.Preset -> id
        is RuntimeModelConfig.Custom -> name
    }
