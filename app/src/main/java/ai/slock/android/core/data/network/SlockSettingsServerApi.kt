package ai.slock.android.core.data.network

import ai.slock.android.core.data.repository.SettingsComputerWorkspaceSnapshot
import ai.slock.android.core.data.repository.SettingsComputersSnapshot
import ai.slock.android.core.data.repository.SettingsPlanUsageSnapshot
import ai.slock.android.core.data.repository.SettingsServerActionResult
import ai.slock.android.core.data.repository.SettingsServerAdminSnapshot
import ai.slock.android.core.data.repository.SettingsServerOnboardingSnapshot
import ai.slock.android.core.state.EmptyReason
import ai.slock.android.core.state.ScreenError
import ai.slock.compose.data.DataResult
import ai.slock.compose.network.SlockRequestPriority
import java.io.IOException
import kotlinx.coroutines.CancellationException
import okhttp3.RequestBody

internal interface SlockSettingsServerApi {
    suspend fun computers(
        priority: SlockRequestPriority,
        serverId: String
    ): DataResult<SettingsComputersSnapshot>

    suspend fun machineWorkspaces(
        priority: SlockRequestPriority,
        serverId: String,
        machineId: String
    ): DataResult<List<SettingsComputerWorkspaceSnapshot>>

    suspend fun serverAdmins(
        priority: SlockRequestPriority,
        serverId: String
    ): DataResult<List<SettingsServerAdminSnapshot>>

    suspend fun serverUsage(
        priority: SlockRequestPriority,
        serverId: String
    ): DataResult<List<SettingsPlanUsageSnapshot>>

    suspend fun billingSubscription(
        priority: SlockRequestPriority
    ): DataResult<List<SettingsPlanUsageSnapshot>>

    suspend fun serverOnboarding(
        priority: SlockRequestPriority,
        serverId: String
    ): DataResult<SettingsServerOnboardingSnapshot>

    suspend fun updateServerProfile(
        priority: SlockRequestPriority,
        serverId: String,
        body: RequestBody
    ): DataResult<Unit>

    suspend fun createServerInvite(
        priority: SlockRequestPriority,
        serverId: String,
        body: RequestBody
    ): DataResult<SettingsServerActionResult>

    suspend fun deleteServer(
        priority: SlockRequestPriority,
        serverId: String
    ): DataResult<SettingsServerActionResult>
}

internal class SlockSettingsServerApiClient(
    private val api: SlockApi,
    private val warningLogger: (String) -> Unit = {}
) : SlockSettingsServerApi {
    override suspend fun computers(
        priority: SlockRequestPriority,
        serverId: String
    ): DataResult<SettingsComputersSnapshot> =
        executeTypedSettings(
            source = "settings_machines",
            contractCode = "settings_machines_contract_mismatch",
            block = { api.settingsMachines(priority, serverId) }
        ) { bodyText ->
            val snapshot = ai.slock.compose.data.parseSharedSettingsComputers(bodyText)
                ?: settingsContractMismatch("settings_machines_contract_mismatch")
            if (snapshot.computers.isEmpty()) {
                warningLogger("empty_items source=settings_machines stage=list_extract root=machines")
            }
            snapshot
        }

    override suspend fun machineWorkspaces(
        priority: SlockRequestPriority,
        serverId: String,
        machineId: String
    ): DataResult<List<SettingsComputerWorkspaceSnapshot>> =
        executeTypedSettings(
            source = "settings_machine_workspaces",
            contractCode = "settings_machine_workspaces_contract_mismatch",
            block = { api.settingsMachineWorkspaces(priority, serverId, machineId) }
        ) { bodyText ->
            val workspaces = ai.slock.compose.data.parseSharedSettingsMachineWorkspaces(bodyText)
                ?: settingsContractMismatch("settings_machine_workspaces_contract_mismatch")
            if (workspaces.isEmpty()) {
                warningLogger("empty_items source=settings_machine_workspaces stage=list_extract root=array")
            }
            workspaces
        }

    override suspend fun serverAdmins(
        priority: SlockRequestPriority,
        serverId: String
    ): DataResult<List<SettingsServerAdminSnapshot>> =
        executeTypedSettings(
            source = "settings_server_members",
            contractCode = "settings_server_members_contract_mismatch",
            block = { api.serverMembers(priority, serverId) }
        ) { bodyText ->
            ai.slock.compose.data.parseSharedSettingsServerAdmins(bodyText)
                ?: settingsContractMismatch("settings_server_members_contract_mismatch")
        }

    override suspend fun serverUsage(
        priority: SlockRequestPriority,
        serverId: String
    ): DataResult<List<SettingsPlanUsageSnapshot>> =
        executeTypedSettings(
            source = "settings_server_usage",
            contractCode = "settings_server_usage_contract_mismatch",
            block = { api.settingsServerUsage(priority, serverId) }
        ) { bodyText ->
            ai.slock.compose.data.parseSharedSettingsServerUsage(bodyText)
                ?: settingsContractMismatch("settings_server_usage_contract_mismatch")
        }

    override suspend fun billingSubscription(
        priority: SlockRequestPriority
    ): DataResult<List<SettingsPlanUsageSnapshot>> =
        executeTypedSettings(
            source = "settings_billing_subscription",
            contractCode = "settings_billing_subscription_contract_mismatch",
            block = { api.settingsBillingSubscription(priority) }
        ) { bodyText ->
            ai.slock.compose.data.parseSharedSettingsBillingSubscription(bodyText)
                ?: settingsContractMismatch("settings_billing_subscription_contract_mismatch")
        }

    override suspend fun serverOnboarding(
        priority: SlockRequestPriority,
        serverId: String
    ): DataResult<SettingsServerOnboardingSnapshot> =
        executeTypedSettings(
            source = "settings_server_onboarding_settings",
            contractCode = "settings_server_onboarding_contract_mismatch",
            block = { api.settingsServerOnboardingSettings(priority, serverId) }
        ) { bodyText ->
            ai.slock.compose.data.parseSharedSettingsServerOnboarding(bodyText)
                ?: settingsContractMismatch("settings_server_onboarding_contract_mismatch")
        }

    override suspend fun updateServerProfile(
        priority: SlockRequestPriority,
        serverId: String,
        body: RequestBody
    ): DataResult<Unit> =
        executeTypedSettings(
            source = "settings_server_profile_update",
            contractCode = "settings_server_profile_contract_mismatch",
            block = { api.updateSettingsServerProfile(priority, serverId, body) }
        ) { bodyText ->
            if (!ai.slock.compose.data.parseSharedSettingsServerProfileUpdated(bodyText)) {
                settingsContractMismatch("settings_server_profile_contract_mismatch")
            }
            Unit
        }

    override suspend fun createServerInvite(
        priority: SlockRequestPriority,
        serverId: String,
        body: RequestBody
    ): DataResult<SettingsServerActionResult> =
        executeTypedSettings(
            source = "settings_server_invite_create",
            contractCode = "settings_server_invite_contract_mismatch",
            block = { api.createSettingsServerInvite(priority, serverId, body) }
        ) { bodyText ->
            val result = ai.slock.compose.data.parseSharedSettingsServerInviteCreated(bodyText)
                ?: settingsContractMismatch("settings_server_invite_contract_mismatch")
            SettingsServerActionResult(success = result.success, message = result.message)
        }

    override suspend fun deleteServer(
        priority: SlockRequestPriority,
        serverId: String
    ): DataResult<SettingsServerActionResult> =
        executeTypedSettings(
            source = "settings_server_delete",
            contractCode = "settings_server_delete_contract_mismatch",
            block = { api.deleteSettingsServer(priority, serverId) }
        ) { bodyText ->
            val result = ai.slock.compose.data.parseSharedSettingsServerDeleted(bodyText)
                ?: settingsContractMismatch("settings_server_delete_contract_mismatch")
            SettingsServerActionResult(success = result.success, message = result.message)
        }
}

private suspend fun <T> executeTypedSettings(
    source: String,
    contractCode: String,
    block: suspend () -> ApiResponse,
    mapper: (String) -> T
): DataResult<T> {
    var statusCode: Int? = null
    return try {
        val response = block()
        statusCode = response.code()
        if (!response.isSuccessful) {
            if (response.code() == 404) {
                return DataResult.Error(ScreenError(code = contractCode, retryable = false))
            }
            return response.toDataError()
        }
        val body = response.body()?.string().orEmpty()
        if (body.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        DataResult.Content(mapper(body))
    } catch (exception: SettingsContractMismatch) {
        val code =
            if (exception.code.endsWith("_contract_mismatch")) exception.code else contractCode
        DataResult.Error(ScreenError(code = code, retryable = false))
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: IOException) {
        DataResult.Error(ScreenError(code = "network_unavailable", retryable = true))
    } catch (exception: Exception) {
        warningLogSettingsParseFailure(source, statusCode, exception)
        DataResult.Error(ScreenError(code = "network_parse_failed", retryable = true))
    }
}

private fun warningLogSettingsParseFailure(source: String, statusCode: Int?, exception: Exception) {
    // Kept as a tiny helper so parse failures stay non-sensitive.
    @Suppress("UNUSED_VARIABLE")
    val message =
        "parse_failed source=$source stage=response_or_mapper status=${statusCode ?: "unknown"} exception=${exception::class.java.simpleName}"
}

private class SettingsContractMismatch(val code: String) : IllegalArgumentException(code)

private fun settingsContractMismatch(code: String): Nothing = throw SettingsContractMismatch(code)
