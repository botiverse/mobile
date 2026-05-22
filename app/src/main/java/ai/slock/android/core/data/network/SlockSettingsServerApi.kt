package ai.slock.android.core.data.network

import ai.slock.android.core.data.repository.SettingsComputerSnapshot
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
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
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
        ) { element ->
            val root = element.requireObject()
            val machines = root.requireArray("machines")
                .map { it.requireObject().toTypedComputer() }
                .filter { it.id.isNotBlank() || it.name.isNotBlank() }
            if (machines.isEmpty()) {
                warningLogger("empty_items source=settings_machines stage=list_extract rootKeys=${root.safeSettingsKeyList()}")
            }
            SettingsComputersSnapshot(
                computers = machines,
                canAddComputer = root.boolean("canAddComputer") ?: false,
                addComputerUnavailableReason = root.string("addComputerUnavailableReason"),
                latestDaemonVersion = root.string("latestDaemonVersion")
            )
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
        ) { element ->
            val workspaces = element.requireRootArray()
                .map { it.requireObject().toTypedWorkspace() }
                .filter { it.id.isNotBlank() || it.path.isNotBlank() }
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
        ) { element ->
            element.requireRootArray()
                .map { it.requireObject() }
                .filter { it.string("role")?.lowercase() == "admin" }
                .map { it.toTypedServerAdmin() }
        }

    override suspend fun serverUsage(
        priority: SlockRequestPriority,
        serverId: String
    ): DataResult<List<SettingsPlanUsageSnapshot>> =
        executeTypedSettings(
            source = "settings_server_usage",
            contractCode = "settings_server_usage_contract_mismatch",
            block = { api.settingsServerUsage(priority, serverId) }
        ) { element ->
            val root = element.requireObject()
            listOf(
                root.requireUsageRow("Agents", "agents"),
                root.requireUsageRow("Computers", "machines"),
                root.requireUsageRow("Channels", "channels")
            )
        }

    override suspend fun billingSubscription(
        priority: SlockRequestPriority
    ): DataResult<List<SettingsPlanUsageSnapshot>> =
        executeTypedSettings(
            source = "settings_billing_subscription",
            contractCode = "settings_billing_subscription_contract_mismatch",
            block = { api.settingsBillingSubscription(priority) }
        ) { element ->
            val root = element.requireObject()
            val subscriptionElement = root.get("subscription")
            val stripeConfigured = root.requireBoolean("stripeConfigured")
            buildList {
                val subscription =
                    if (subscriptionElement == null || subscriptionElement.isJsonNull) {
                        null
                    } else {
                        subscriptionElement.requireObject()
                    }
                add(
                    SettingsPlanUsageSnapshot(
                        label = "Billing Status",
                        value = subscription?.string("status")?.toSettingsTitleCase() ?: "No subscription"
                    )
                )
                subscription?.string("currentPeriodEnd")?.let { currentPeriodEnd ->
                    add(SettingsPlanUsageSnapshot(label = "Current Period End", value = currentPeriodEnd))
                }
                add(SettingsPlanUsageSnapshot(label = "Stripe Configured", value = stripeConfigured.toString()))
            }
        }

    override suspend fun serverOnboarding(
        priority: SlockRequestPriority,
        serverId: String
    ): DataResult<SettingsServerOnboardingSnapshot> =
        executeTypedSettings(
            source = "settings_server_onboarding_settings",
            contractCode = "settings_server_onboarding_contract_mismatch",
            block = { api.settingsServerOnboardingSettings(priority, serverId) }
        ) { element ->
            val root = element.requireObject()
            val optOut = root.boolean("setupModalReminderOptOut", "onboardingReminderOptOut")
                ?: settingsContractMismatch("settings_server_onboarding_contract_mismatch")
            SettingsServerOnboardingSnapshot(
                setupModalReminderOptOut = optOut,
                statusLabel = if (optOut) "Reminder muted" else "Reminder active",
                detail = if (optOut) {
                    "The onboarding setup reminder is muted for this server."
                } else {
                    "The onboarding setup reminder can appear when the server has no computer or agent."
                }
            )
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
        ) { element ->
            val root = element.requireObject()
            if (root.string("id") == null || root.string("name") == null) {
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
        ) { element ->
            val root = element.requireObject()
            val email = root.string("email")
            val id = root.string("id")
            if (email == null && id == null) {
                settingsContractMismatch("settings_server_invite_contract_mismatch")
            }
            SettingsServerActionResult(
                success = true,
                message = email?.let { "Invite created for $it" }
            )
        }

    override suspend fun deleteServer(
        priority: SlockRequestPriority,
        serverId: String
    ): DataResult<SettingsServerActionResult> =
        executeTypedSettings(
            source = "settings_server_delete",
            contractCode = "settings_server_delete_contract_mismatch",
            block = { api.deleteSettingsServer(priority, serverId) }
        ) { element ->
            val root = element.requireObject()
            if (root.requireBoolean("ok") != true) {
                settingsContractMismatch("settings_server_delete_contract_mismatch")
            }
            SettingsServerActionResult(success = true)
        }
}

private suspend fun <T> executeTypedSettings(
    source: String,
    contractCode: String,
    block: suspend () -> ApiResponse,
    mapper: (JsonElement) -> T
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
        val element = Gson().fromJson(body, JsonElement::class.java)
        if (element == null || element.isJsonNull) {
            DataResult.Empty(EmptyReason.NoSettingsAvailable)
        } else {
            DataResult.Content(mapper(element))
        }
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

private fun JsonElement.requireObject(): JsonObject =
    if (isJsonObject) asJsonObject else settingsContractMismatch("settings_json_object_required")

private fun JsonElement.requireRootArray(): List<JsonElement> =
    if (isJsonArray) asJsonArray.toList() else settingsContractMismatch("settings_json_array_required")

private fun JsonObject.requireArray(name: String): List<JsonElement> {
    val value = get(name)
    if (value == null || !value.isJsonArray) settingsContractMismatch("settings_array_required")
    return value.asJsonArray.toList()
}

private fun JsonObject.requireBoolean(name: String): Boolean =
    boolean(name) ?: settingsContractMismatch("settings_boolean_required")

private fun JsonObject.requireUsageRow(label: String, name: String): SettingsPlanUsageSnapshot {
    val value = string(name) ?: int(name)?.toString()
        ?: settingsContractMismatch("settings_server_usage_contract_mismatch")
    return SettingsPlanUsageSnapshot(label = label, value = value)
}

private fun JsonObject.toTypedComputer(): SettingsComputerSnapshot {
    val id = string("id") ?: ""
    val hostname = string("hostname")
    val name = string("name") ?: hostname ?: id.ifBlank { "Unknown computer" }
    val daemonVersion = string("daemonVersion") ?: "daemon unavailable"
    val status = string("status")?.lowercase()
    return SettingsComputerSnapshot(
        id = id.ifBlank { name },
        name = name,
        daemonVersion = daemonVersion,
        online = boolean("online") ?: (status in setOf("online", "connected", "active", "ready", "running")),
        hostname = hostname,
        platform = string("platform"),
        lastSeenLabel = string("lastSeenAt", "updatedAt")
    )
}

private fun JsonObject.toTypedWorkspace(): SettingsComputerWorkspaceSnapshot {
    val path = string("path") ?: ""
    val name = string("name") ?: path.substringAfterLast('/').ifBlank { path.ifBlank { "Workspace" } }
    val id = string("id") ?: path.ifBlank { name }
    return SettingsComputerWorkspaceSnapshot(
        id = id,
        name = name,
        path = path.ifBlank { name },
        statusLabel = string("status"),
        lastActiveLabel = string("lastActiveAt", "updatedAt")
    )
}

private fun JsonObject.toTypedServerAdmin(): SettingsServerAdminSnapshot {
    val id = string("userId") ?: settingsContractMismatch("settings_server_members_contract_mismatch")
    val name = string("displayName", "name", "email") ?: id
    return SettingsServerAdminSnapshot(
        id = id,
        name = name,
        detail = string("email"),
        role = string("role")?.toSettingsTitleCase()
    )
}

private fun String.toSettingsTitleCase(): String =
    replace("_", " ")
        .replace("-", " ")
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

private fun JsonObject.safeSettingsKeyList(): String =
    entrySet().map { it.key }.sorted().joinToString("|").ifBlank { "none" }
