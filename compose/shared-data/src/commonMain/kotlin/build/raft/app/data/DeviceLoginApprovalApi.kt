package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.reportUnknownTypeDropped
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Approving a device login from the app: the code `raft-computer setup` shows when it runs over
 * SSH (or anywhere without a browser), typed into Add Computer. The web does the same from its
 * Add Computer window and its device login page (botiverse/slock 329e1700f7, #15612:
 * `packages/web/src/components/auth/useDeviceLoginApproval.ts`, `deviceLoginApproval.ts`).
 *
 * Server contract, botiverse/slock staging 23ae82825 `packages/server/src/routes/deviceAuth.ts`
 * (`describe`, `approve`, `answerApproval`, `userCodeFailureStatus`), both behind `requireAuth`,
 * which admits a person's own `full` sign-in (the app's, from password, OAuth or app login) and
 * never a device login's session:
 *
 *     POST /api/auth/device/describe
 *       { userCode } → 200 { userCode, clientName, requestedAt, requester: { ip, location },
 *                            approvalTicket, grant? }
 *       grant, a one-time grant (absent for a sign-in): { kind, machineName, hostname, os, servers? }
 *         servers (computer_attach, computer_migrate): the ones this person may add computers to,
 *         [{ id, slug, name }] by name (services/deviceLoginGrantChoices.ts listAttachableServers);
 *         agent_key carries requestedAgentId and agents instead.
 *       404 user_code_invalid · 409 already_resolved · 410 expired · 400 user_code_required ·
 *       404 device_login_disabled
 *     POST /api/auth/device/approve
 *       { userCode, approve: true, approvalTicket, serverId } | { userCode, approve: false }
 *       → 200 { ok: true, action: "approved" | "denied" }
 *       403 second_factor_step_up_required { methods } before anything changes (routes/secondFactor.ts
 *         refuseUnverifiedApprover: two-step verification is on and this sign-in hasn't passed
 *         it; POST /auth/second-factor/step-up, then the same approve)
 *       400 approval_ticket_required | grant_selection_required | grant_kind_unsupported ·
 *       403 grant_selection_not_allowed · 401 auth_required (the approving sign-in was revoked) ·
 *       404 user_code_invalid · 409 already_resolved · 410 expired
 *
 * Refusals are `{ error, code }` (lib/refusal.ts adds `errorCode`); the auth limiter's 429
 * (`{ error }`) and a 5xx carry only the server's words. The code goes in the body, never in an
 * address.
 */
@RaftCoreInternalApi const val DEVICE_LOGIN_DESCRIBE_PATH = "auth/device/describe"
@RaftCoreInternalApi const val DEVICE_LOGIN_APPROVE_PATH = "auth/device/approve"

private val deviceLoginJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** describe's `grant.kind` (slock `db/schema.ts` DeviceLoginGrantKind). */
enum class DeviceLoginGrantKind(val wireName: String) {
    ComputerAttach("computer_attach"),
    ComputerMigrate("computer_migrate"),
    AgentKey("agent_key"),
}

/** A server the person may add a computer to (describe's `grant.servers`). */
data class DeviceLoginServer(val id: String, val slug: String, val name: String)

/**
 * A one-time grant: what the login asks for, and the machine as it reports itself (whoever
 * started the login chose every field). [kind] null: a kind this app doesn't know
 * ([kindWireName] as sent), which only the device login page may explain.
 */
data class DeviceLoginGrant(
    val kind: DeviceLoginGrantKind?,
    val kindWireName: String,
    val machineName: String?,
    val hostname: String?,
    val os: String?,
    val servers: List<DeviceLoginServer> = emptyList(),
)

/** What describe answers for a typed code that can still be approved (web `DeviceLoginRequest`). */
data class DeviceLoginRequest(
    /** The code in its stored form: approve names the request by it. */
    val userCode: String,
    val clientName: String?,
    /** When the login was started (ISO-8601). */
    val requestedAt: String,
    val requesterIp: String?,
    val requesterLocation: String?,
    /** Approve needs it: proof that this person checked this code. */
    val approvalTicket: String,
    /** Null: a sign-in (a device session), not a one-time grant. */
    val grant: DeviceLoginGrant?,
)

@Serializable
@RaftCoreInternalApi data class DeviceLoginDescribeBody(val userCode: String)

@Serializable
@RaftCoreInternalApi data class DeviceLoginApproveBody(
    val userCode: String,
    val approve: Boolean,
    val approvalTicket: String? = null,
    val serverId: String? = null,
)

@Serializable
private data class DeviceLoginServerDto(val id: String = "", val slug: String = "", val name: String = "")

@Serializable
private data class DeviceLoginGrantDto(
    val kind: String = "",
    val machineName: String? = null,
    val hostname: String? = null,
    val os: String? = null,
    val servers: List<DeviceLoginServerDto>? = null,
)

@Serializable
private data class DeviceLoginRequesterDto(val ip: String? = null, val location: String? = null)

@Serializable
private data class DeviceLoginRequestDto(
    val userCode: String? = null,
    val clientName: String? = null,
    val requestedAt: String = "",
    val requester: DeviceLoginRequesterDto? = null,
    val approvalTicket: String = "",
    val grant: DeviceLoginGrantDto? = null,
)

@Serializable
private data class DeviceLoginRefusalDto(val error: String? = null, val code: String? = null)

@RaftCoreInternalApi fun buildDeviceLoginDescribeBody(userCode: String): String =
    deviceLoginJson.encodeToString(DeviceLoginDescribeBody.serializer(), DeviceLoginDescribeBody(userCode))

/**
 * Approve's body: for [serverId] with describe's ticket, or, with none, a denial, which sends
 * neither (web `resolve`).
 */
@RaftCoreInternalApi fun buildDeviceLoginApproveBody(request: DeviceLoginRequest, serverId: String?): String =
    deviceLoginJson.encodeToString(
        DeviceLoginApproveBody.serializer(),
        if (serverId != null) {
            DeviceLoginApproveBody(request.userCode, approve = true, approvalTicket = request.approvalTicket, serverId = serverId)
        } else {
            DeviceLoginApproveBody(request.userCode, approve = false)
        },
    )

/** describe's 200; null when it is not that shape (no code or no ticket). */
@RaftCoreInternalApi fun parseSharedDeviceLoginRequest(bodyText: String?): DeviceLoginRequest? {
    val body = bodyText?.takeIf { it.isNotBlank() } ?: return null
    val wire = runCatching { deviceLoginJson.decodeFromString(DeviceLoginRequestDto.serializer(), body) }.getOrNull() ?: return null
    val userCode = wire.userCode?.takeIf { it.isNotBlank() } ?: return null
    val ticket = wire.approvalTicket.takeIf { it.isNotBlank() } ?: return null
    return DeviceLoginRequest(
        userCode = userCode,
        clientName = wire.clientName.presentOrNull(),
        requestedAt = wire.requestedAt,
        requesterIp = wire.requester?.ip.presentOrNull(),
        requesterLocation = wire.requester?.location.presentOrNull(),
        approvalTicket = ticket,
        grant = wire.grant?.toGrant(),
    )
}

private fun DeviceLoginGrantDto.toGrant(): DeviceLoginGrant {
    val known = DeviceLoginGrantKind.entries.firstOrNull { it.wireName == kind }
    // Web isKnownGrant: refused before any pick, with the device login page offered instead.
    if (known == null) reportUnknownTypeDropped("device_login_describe", "grant.kind", kind, fallback = "device_login_page")
    return DeviceLoginGrant(
        kind = known,
        kindWireName = kind,
        machineName = machineName.presentOrNull(),
        hostname = hostname.presentOrNull(),
        os = os.presentOrNull(),
        servers = servers.orEmpty().mapNotNull { server -> server.id.takeIf { it.isNotBlank() }?.let { DeviceLoginServer(it, server.slug, server.name) } },
    )
}

/** An empty value reads as none, as the web's `filter(Boolean)` and `name ? … : notGiven` do. */
private fun String?.presentOrNull(): String? = this?.takeIf { it.isNotBlank() }

/** A refused describe or approve: the server's code and words, and the response's trace id. */
private fun HomeNetworkResponse.deviceLoginRefusal(): ScreenError {
    val refusal =
        (bodyText?.takeIf { it.isNotBlank() } ?: errorBodyText)?.takeIf { it.isNotBlank() }?.let { body ->
            runCatching { deviceLoginJson.decodeFromString(DeviceLoginRefusalDto.serializer(), body) }.getOrNull()
        }
    val serverCode = refusal?.code?.takeIf { it.isNotBlank() }
    return ScreenError(
        code = serverCode ?: "http_$code",
        retryable = false,
        serverMessage = refusal?.error?.takeIf { it.isNotBlank() },
        serverCode = serverCode,
        traceId = traceId,
    )
}

/** describe and approve through the shared network client (user-blocking: Foreground). */
class DeviceLoginApprovalRepository(private val networkClient: RaftNetworkClient) {
    suspend fun describe(userCode: String): DataResult<DeviceLoginRequest> =
        send(DEVICE_LOGIN_DESCRIBE_PATH, buildDeviceLoginDescribeBody(userCode)) { response ->
            parseSharedDeviceLoginRequest(response.bodyText)?.let { DataResult.Content(it) }
                ?: DataResult.Error(ScreenError("device_login_describe_contract_mismatch", retryable = false, traceId = response.traceId))
        }

    /** Approve [request] for [serverId]; a 403 step-up refusal comes back as [secondFactorStepUpErrorOrNull]'s. */
    suspend fun approve(request: DeviceLoginRequest, serverId: String): DataResult<Unit> =
        send(DEVICE_LOGIN_APPROVE_PATH, buildDeviceLoginApproveBody(request, serverId)) { DataResult.Content(Unit) }

    suspend fun deny(request: DeviceLoginRequest): DataResult<Unit> =
        send(DEVICE_LOGIN_APPROVE_PATH, buildDeviceLoginApproveBody(request, serverId = null)) { DataResult.Content(Unit) }

    private suspend fun <T> send(path: String, body: String, onSuccess: (HomeNetworkResponse) -> DataResult<T>): DataResult<T> {
        val response =
            try {
                networkClient.request(priority = DataRequestPriority.Foreground, path = path, method = "POST", bodyText = body)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // No answer, so no trace id (AGENTS.md "Errors carry the trace ID").
                return DataResult.Error(ScreenError("network", retryable = true, traceId = null))
            }
        if (response.isSuccessful) return onSuccess(response)
        response.secondFactorStepUpErrorOrNull<T>()?.let { return it }
        return DataResult.Error(response.deviceLoginRefusal())
    }
}
