package ai.slock.android.core.data.network

import ai.slock.android.core.data.auth.AuthSession
import ai.slock.android.core.data.auth.AuthSessionStore
import ai.slock.android.core.data.repository.AccountProfileSnapshot
import ai.slock.android.core.data.repository.AccountProfileUpdateRequest
import ai.slock.android.core.data.repository.AccountSettingsSnapshot
import ai.slock.android.core.data.repository.ConnectedAccountSnapshot
import ai.slock.android.core.data.repository.ConnectedAccountStatus
import ai.slock.android.core.data.repository.DataResult
import ai.slock.android.core.data.repository.SessionActionResult
import ai.slock.android.core.data.repository.SettingsComputerSnapshot
import ai.slock.android.core.data.repository.SettingsComputersSnapshot
import ai.slock.android.core.data.repository.SettingsMobileSnapshot
import ai.slock.android.core.data.repository.SettingsMobileToggleSnapshot
import ai.slock.android.core.data.repository.SessionSettingsSnapshot
import ai.slock.android.core.data.repository.SettingsRepository
import ai.slock.android.core.data.repository.SettingsSectionAvailability
import ai.slock.android.core.data.repository.SettingsServerProfileSnapshot
import ai.slock.android.core.data.repository.SettingsServerSectionSnapshot
import ai.slock.android.core.data.repository.SettingsServerSnapshot
import ai.slock.android.core.data.repository.SettingsSessionSnapshot
import ai.slock.android.core.state.EmptyReason
import ai.slock.android.core.state.ScreenError
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class RealSettingsRepository(
    private val store: AuthSessionStore,
    private val api: SlockApi
) : SettingsRepository {
    override suspend fun getAccount(): DataResult<AccountSettingsSnapshot> {
        val session = store.readSession() ?: return authRequired()
        return execute(
            emptyReason = EmptyReason.NoSettingsAvailable,
            block = { api.settingsAccount() },
            mapper = { json -> json.toAccountSettingsSnapshot(session) }
        )
    }

    override suspend fun updateProfile(request: AccountProfileUpdateRequest): DataResult<AccountSettingsSnapshot> {
        val session = store.readSession() ?: return authRequired()
        return execute(
            emptyReason = EmptyReason.NoSettingsAvailable,
            block = { api.updateSettingsAccount(request.toJsonBody()) },
            mapper = { json -> json.toAccountSettingsSnapshot(session) }
        )
    }

    override suspend fun getSessions(): DataResult<SessionSettingsSnapshot> {
        store.readSession() ?: return authRequired()
        return execute(
            emptyReason = EmptyReason.NoSettingsAvailable,
            block = { api.settingsSessions() },
            emptyWhen = { it.sessions.isEmpty() },
            mapper = { json -> json.toSessionSettingsSnapshot() }
        ).mapSettingsContractMismatch("settings_sessions_contract_mismatch")
    }

    override suspend fun revokeSession(sessionId: String): DataResult<SessionActionResult> {
        if (sessionId.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        store.readSession() ?: return authRequired()
        return execute(
            emptyReason = EmptyReason.NoSettingsAvailable,
            block = { api.revokeSettingsSession(sessionId) },
            mapper = { json ->
                SessionActionResult(
                    sessionId = json.string("sessionId", "id") ?: sessionId,
                    revoked = json.boolean("revoked", "ok", "success") ?: true
                )
            }
        ).mapSettingsContractMismatch("settings_session_revoke_contract_mismatch")
    }

    override suspend fun getComputers(): DataResult<SettingsComputersSnapshot> {
        val session = store.readSession() ?: return authRequired()
        return DataResult.Content(
            SettingsComputersSnapshot(
                computers = listOf(
                    SettingsComputerSnapshot(
                        id = "current-device",
                        name = session.user.bestDisplayName,
                        daemonVersion = "daemon unavailable",
                        online = true
                    )
                ),
                canAddComputer = false,
                addComputerUnavailableReason = "Computer management API is not wired on Android yet."
            )
        )
    }

    override suspend fun getServerSettings(): DataResult<SettingsServerSnapshot> {
        val session = store.readSession() ?: return authRequired()
        val server = session.servers.firstOrNull { it.id == session.currentServerId }
        val name = server?.name ?: "Slock"
        return DataResult.Content(
            SettingsServerSnapshot(
                profile = SettingsServerProfileSnapshot(
                    name = name,
                    slug = name.toSettingsSlug(),
                    canSave = false
                ),
                sections = listOf(
                    SettingsServerSectionSnapshot(
                        key = "admins",
                        title = "Admins",
                        detail = "Admin management endpoint is not wired on Android yet."
                    ),
                    SettingsServerSectionSnapshot(
                        key = "billing",
                        title = "Plan & Billing",
                        detail = "Billing settings are unavailable until the contract is confirmed."
                    ),
                    SettingsServerSectionSnapshot(
                        key = "pending_invites",
                        title = "Pending Invites",
                        detail = "Invite listing is unavailable until the contract is confirmed."
                    ),
                    SettingsServerSectionSnapshot(
                        key = "join_links",
                        title = "Join Links",
                        detail = "Join link management is unavailable until the contract is confirmed."
                    ),
                    SettingsServerSectionSnapshot(
                        key = "onboarding_agent",
                        title = "Onboarding Agent",
                        detail = "Onboarding agent settings are not wired on Android yet."
                    ),
                    SettingsServerSectionSnapshot(
                        key = "danger",
                        title = "Danger Zone",
                        detail = "Delete Server remains disabled until the endpoint and owner permission contract are confirmed.",
                        state = SettingsSectionAvailability.NoPermission
                    )
                )
            )
        )
    }

    override suspend fun getMobileSettings(): DataResult<SettingsMobileSnapshot> {
        store.readSession() ?: return authRequired()
        return DataResult.Content(
            SettingsMobileSnapshot(
                pushNotifications = SettingsMobileToggleSnapshot(
                    enabled = false,
                    available = true,
                    statusLabel = "Disabled",
                    detail = "Notifications can arrive while Slock is in the background. This Android build can preview the local state before the push API is connected."
                ),
                muteServer = SettingsMobileToggleSnapshot(
                    enabled = false,
                    available = false,
                    statusLabel = "Not muted",
                    detail = "Server mute is unavailable until the mobile settings endpoint is confirmed."
                ),
                sendTestEnabled = true
            )
        )
    }
}

private fun <T> DataResult<T>.mapSettingsContractMismatch(code: String): DataResult<T> =
    when (this) {
        is DataResult.Error -> {
            if (error.code == "http_404") {
                DataResult.Error(ScreenError(code = code, retryable = false))
            } else {
                this
            }
        }
        else -> this
    }

private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

private fun AccountProfileUpdateRequest.toJsonBody() =
    Gson().toJson(
        mapOf(
            "displayName" to displayName,
            "handle" to handle,
            "email" to email
        )
    ).toRequestBody(jsonMediaType)

private fun JsonObject.toAccountSettingsSnapshot(session: AuthSession): AccountSettingsSnapshot {
    val user = objectValue("user", "account", "profile") ?: this
    val displayName = user.string("displayName", "name")
        ?: session.user.bestDisplayName
    val email = user.string("email") ?: session.user.email
    val handle = user.string("handle", "name", "username")
        ?: email.takeIf { it.isNotBlank() }
        ?: displayName
    val role = user.string("role", "roleLabel")
        ?: session.servers.firstOrNull { it.id == session.currentServerId }?.role
    val connectedAccounts = items("connectedAccounts", "providers", "accounts")
        .map { it.asObject().toConnectedAccount() }
        .ifEmpty {
            listOf(
                ConnectedAccountSnapshot(
                    provider = "Email password",
                    identifier = email,
                    status = ConnectedAccountStatus.Connected
                )
            )
        }
    return AccountSettingsSnapshot(
        profile = AccountProfileSnapshot(
            userId = user.string("id", "userId") ?: session.user.id,
            displayName = displayName,
            handle = handle,
            email = email,
            avatarLabel = displayName.take(2).uppercase(),
            avatarUrl = user.string("avatarUrl", "avatar"),
            verified = user.boolean("emailVerified", "verified") ?: session.user.emailVerified ?: false,
            roleLabel = role?.toRoleLabel()
        ),
        connectedAccounts = connectedAccounts,
        canEditProfile = boolean("canEditProfile", "canEdit") ?: true
    )
}

private fun JsonObject.toConnectedAccount(): ConnectedAccountSnapshot {
    val provider = string("provider", "name", "type") ?: "Account"
    return ConnectedAccountSnapshot(
        provider = provider,
        identifier = string("identifier", "email", "username", "url") ?: provider,
        status = string("status")?.toConnectedAccountStatus()
            ?: if (boolean("connected", "enabled") == false) {
                ConnectedAccountStatus.NotConnected
            } else {
                ConnectedAccountStatus.Connected
            }
    )
}

private fun JsonObject.toSessionSettingsSnapshot(): SessionSettingsSnapshot {
    val sessions = items("sessions", "items").map { it.asObject().toSessionSnapshot() }
    return SessionSettingsSnapshot(
        sessions = sessions,
        canRevokeOtherSessions = boolean("canRevokeOtherSessions", "canRevokeOthers") ?: true
    )
}

private fun JsonObject.toSessionSnapshot(): SettingsSessionSnapshot {
    val current = boolean("current", "isCurrent") ?: false
    return SettingsSessionSnapshot(
        id = string("id", "sessionId") ?: "unknown-session",
        deviceName = string("deviceName", "device", "name") ?: "Unknown device",
        browserLabel = string("browserLabel", "browser", "client", "userAgent") ?: "Unknown client",
        locationLabel = string("locationLabel", "location", "ipLabel") ?: "Unknown location",
        lastActiveLabel = string("lastActiveLabel", "lastActiveAt", "updatedAt", "createdAt") ?: "Unknown activity",
        current = current,
        revokeEnabled = boolean("revokeEnabled", "canRevoke") ?: !current
    )
}

private fun JsonObject.objectValue(vararg names: String): JsonObject? {
    for (name in names) {
        val value = get(name)
        if (value != null && value.isJsonObject) return value.asJsonObject
    }
    val data = get("data")
    if (data != null && data.isJsonObject) return data.asJsonObject.objectValue(*names) ?: data.asJsonObject
    return null
}

private fun String.toConnectedAccountStatus(): ConnectedAccountStatus =
    when (lowercase()) {
        "connected", "enabled", "active" -> ConnectedAccountStatus.Connected
        "not_connected", "notconnected", "disconnected", "disabled" -> ConnectedAccountStatus.NotConnected
        "loading", "pending" -> ConnectedAccountStatus.Loading
        "failed", "error" -> ConnectedAccountStatus.Failed
        else -> ConnectedAccountStatus.Connected
    }

private fun String.toRoleLabel(): String =
    replace("_", " ")
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

private fun String.toSettingsSlug(): String =
    lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "server" }
