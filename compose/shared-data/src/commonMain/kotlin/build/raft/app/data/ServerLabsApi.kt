package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.model.ServerId
import build.raft.app.reportProjectionRowDropped
import build.raft.app.reportUnknownTypeDropped
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/*
 * Server Labs (web Settings > Labs), botiverse/slock staging aa270b415d44:
 *  - packages/server/src/routes/serverLabs.ts (mounted at `servers/:id/labs`; the human routes
 *    need `X-Server-Id` equal to `:id`, else 400 `server_scope_required` / 403
 *    `server_scope_forbidden`).
 *  - packages/server/src/services/serverLabService.ts (`ServerLabsReadModel`,
 *    `ServerLabMutationResult`, `ServerLabServiceErrorCode`).
 *  - packages/web/src/utils/serverLabsSettings.ts (`normalizeServerLabSettingsReadback`,
 *    `getServerLabSettingsAuthority`, the enrollment rules) and store/serverLabsSettingsStore.ts.
 *
 * `GET servers/:id/labs` -> `{ serverId, accessEnabled, version, canManageAccess,
 *   canManageEnrollments, labs: [{ labKey, name, description, state, enrolled, effective,
 *   updatedAt }] }` (draft labs, and retired ones this server never enrolled in, are left out by
 *   the server).
 * `PATCH servers/:id/labs/access` (owner) and `PUT servers/:id/labs/:labKey` (owner/admin), body
 *   `{ enabled, expectedVersion }` -> `{ applied, auditEventId, ...readback }`.
 * Errors `{ error, code }`: 404 `server_not_found` / `lab_not_found`; 403
 *   `server_membership_required` / `server_owner_required` / `server_admin_required`; 409
 *   `server_labs_version_conflict` (+ `currentVersion`), `server_labs_access_disabled`,
 *   `lab_not_open`; 400 `invalid_enabled` / `invalid_expected_version` / `invalid_lab_key`.
 *
 * Field names are the server's exact camelCase; no aliases.
 */

private val serverLabsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** The server's `LAB_KEY_RE`: a key outside it is never sent (the route answers 400). */
private val ServerLabKeyPattern = Regex("^[a-z0-9][a-z0-9_.-]{0,127}$")

/**
 * Retired experiments whose flag record still exists (older mobile apps read
 * `attachment_comments_v0`): never Labs entries (web `RETIRED_SERVER_LAB_KEYS`).
 */
private val RetiredServerLabKeys: Set<String> = setOf("attachment_comments_v0")

@RaftCoreInternalApi fun isServerLabKey(labKey: String): Boolean = ServerLabKeyPattern.matches(labKey)

/** `ServerLabCatalogState`. [Unknown]: a value this build does not know, shown read-only. */
@RaftCoreInternalApi enum class ServerLabState(val wire: String?) {
    Draft("draft"),
    Open("open"),
    Paused("paused"),
    Retired("retired"),
    Unknown(null),
    ;

    companion object {
        fun fromWire(value: String): ServerLabState = entries.firstOrNull { it.wire == value } ?: Unknown
    }
}

@RaftCoreInternalApi data class ServerLab(
    val key: String,
    val name: String,
    val description: String,
    val state: ServerLabState,
    val enrolled: Boolean,
    val effective: Boolean,
)

/** The page's readback (web `ServerLabSettingsReadback`). */
@RaftCoreInternalApi data class ServerLabsReadback(
    val serverId: String,
    /** `version`: the cursor every write sends back as `expectedVersion`. */
    val version: Long,
    /** `accessEnabled`: the owner's master gate. */
    val masterEnabled: Boolean,
    /** `canManageAccess`; null when the server leaves it out (the role decides, as on the web). */
    val canManageAccess: Boolean?,
    /** `canManageEnrollments`; null when the server leaves it out. */
    val canManageEnrollments: Boolean?,
    val labs: List<ServerLab>,
)

@Serializable
private data class ServerLabWireDto(
    val labKey: String,
    val name: String,
    val description: String,
    val state: String,
    val enrolled: Boolean,
    val effective: Boolean,
    val updatedAt: String? = null,
)

@Serializable
private data class ServerLabsReadbackWireDto(
    val serverId: String,
    val accessEnabled: Boolean,
    val version: Long,
    val canManageAccess: Boolean? = null,
    val canManageEnrollments: Boolean? = null,
    val labs: List<JsonElement>,
)

@Serializable
private data class ServerLabsMutationWireDto(
    val applied: Boolean,
    val auditEventId: String? = null,
)

@Serializable
private data class ServerLabsErrorWireDto(
    val error: String? = null,
    val code: String? = null,
    val currentVersion: Long? = null,
)

/** The body of both writes: `{ enabled, expectedVersion }`. */
@Serializable
@RaftCoreInternalApi data class ServerLabsMutationRequest(
    val enabled: Boolean,
    val expectedVersion: Long,
)

@RaftCoreInternalApi fun encodeServerLabsMutationRequest(request: ServerLabsMutationRequest): String =
    serverLabsJson.encodeToString(ServerLabsMutationRequest.serializer(), request)

/**
 * Parses a `GET servers/:id/labs` body (or a write's, which carries the same readback). Null when
 * the envelope is unreadable. Lab rows decode one by one: an unreadable row is skipped and logged;
 * when rows came and none decode, the readback is unreadable (null), never an empty list.
 */
@RaftCoreInternalApi fun parseServerLabsReadback(bodyText: String?): ServerLabsReadback? {
    val text = bodyText?.takeIf { it.isNotBlank() } ?: return null
    val wire =
        runCatching { serverLabsJson.decodeFromString(ServerLabsReadbackWireDto.serializer(), text) }.getOrNull()
            ?: return null
    val decoded =
        wire.labs.mapIndexedNotNull { index, element ->
            runCatching { serverLabsJson.decodeFromJsonElement(ServerLabWireDto.serializer(), element) }
                .getOrNull()
                .also { if (it == null) reportProjectionRowDropped(source = "server_labs", reason = "undecodable", index = index) }
        }
    if (wire.labs.isNotEmpty() && decoded.isEmpty()) return null
    val labs =
        decoded
            .filterNot { it.labKey in RetiredServerLabKeys }
            .map { lab ->
                val state = ServerLabState.fromWire(lab.state)
                if (state == ServerLabState.Unknown) {
                    reportUnknownTypeDropped(source = "server_labs", field = "state", value = lab.state, fallback = "read_only")
                }
                ServerLab(
                    key = lab.labKey,
                    name = lab.name,
                    description = lab.description,
                    state = state,
                    enrolled = lab.enrolled,
                    effective = lab.effective,
                )
            }
    return ServerLabsReadback(
        serverId = wire.serverId,
        version = wire.version,
        masterEnabled = wire.accessEnabled,
        canManageAccess = wire.canManageAccess,
        canManageEnrollments = wire.canManageEnrollments,
        labs = labs,
    )
}

/** A write's answer: whether it changed anything, and the readback after it. */
@RaftCoreInternalApi data class ServerLabsMutationResult(
    val applied: Boolean,
    val readback: ServerLabsReadback,
)

@RaftCoreInternalApi fun parseServerLabsMutation(bodyText: String?): ServerLabsMutationResult? {
    val readback = parseServerLabsReadback(bodyText) ?: return null
    val wire =
        runCatching { serverLabsJson.decodeFromString(ServerLabsMutationWireDto.serializer(), bodyText.orEmpty()) }.getOrNull()
            ?: return null
    return ServerLabsMutationResult(applied = wire.applied, readback = readback)
}

/** A refusal's `{ code, currentVersion }` (lower-case service codes). */
@RaftCoreInternalApi data class ServerLabsErrorBody(
    val code: String?,
    val message: String?,
    val currentVersion: Long?,
)

@RaftCoreInternalApi fun parseServerLabsError(bodyText: String?): ServerLabsErrorBody? {
    val text = bodyText?.takeIf { it.isNotBlank() } ?: return null
    val wire = runCatching { serverLabsJson.decodeFromString(ServerLabsErrorWireDto.serializer(), text) }.getOrNull() ?: return null
    return ServerLabsErrorBody(code = wire.code, message = wire.error, currentVersion = wire.currentVersion)
}

@RaftCoreInternalApi const val SERVER_LABS_VERSION_CONFLICT_CODE = "server_labs_version_conflict"

/** Why a Labs request failed; [error] carries the response's trace id. */
@RaftCoreInternalApi data class ServerLabsFailure(
    val error: ScreenError,
    /** The service's code (`server_labs_version_conflict`, `lab_not_open`, ...), null without one. */
    val serverCode: String? = error.serverCode,
) {
    val versionConflict: Boolean
        get() = serverCode == SERVER_LABS_VERSION_CONFLICT_CODE
}

@RaftCoreInternalApi sealed interface ServerLabsLoadResult {
    data class Loaded(val readback: ServerLabsReadback) : ServerLabsLoadResult

    /** 404: the server has no Labs API (web `unavailable`). */
    data object Unavailable : ServerLabsLoadResult

    data class Failed(val failure: ServerLabsFailure) : ServerLabsLoadResult
}

@RaftCoreInternalApi sealed interface ServerLabsMutationOutcome {
    data class Done(val result: ServerLabsMutationResult) : ServerLabsMutationOutcome

    data class Failed(val failure: ServerLabsFailure) : ServerLabsMutationOutcome
}

@RaftCoreInternalApi interface ServerLabsRepository {
    suspend fun load(serverId: String): ServerLabsLoadResult

    suspend fun setAccess(serverId: String, request: ServerLabsMutationRequest): ServerLabsMutationOutcome

    suspend fun setEnrollment(serverId: String, labKey: String, request: ServerLabsMutationRequest): ServerLabsMutationOutcome
}

@RaftCoreInternalApi class NetworkServerLabsRepository(
    private val networkClient: RaftNetworkClient,
) : ServerLabsRepository {
    override suspend fun load(serverId: String): ServerLabsLoadResult {
        val response =
            runCatchingPreservingCancellation {
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "servers/$serverId/labs",
                    serverIdOverride = ServerId(serverId),
                )
            }.getOrElse { return ServerLabsLoadResult.Failed(transportFailure()) }
        if (response.code == 404) return ServerLabsLoadResult.Unavailable
        if (!response.isSuccessful) return ServerLabsLoadResult.Failed(response.failure())
        val readback = parseServerLabsReadback(response.bodyText) ?: return ServerLabsLoadResult.Failed(unreadable(response))
        return ServerLabsLoadResult.Loaded(readback)
    }

    override suspend fun setAccess(serverId: String, request: ServerLabsMutationRequest): ServerLabsMutationOutcome =
        mutate(serverId, "servers/$serverId/labs/access", "PATCH", request)

    override suspend fun setEnrollment(
        serverId: String,
        labKey: String,
        request: ServerLabsMutationRequest,
    ): ServerLabsMutationOutcome {
        if (!isServerLabKey(labKey)) {
            return ServerLabsMutationOutcome.Failed(ServerLabsFailure(ScreenError(code = "invalid_lab_key", retryable = false, traceId = null)))
        }
        return mutate(serverId, "servers/$serverId/labs/$labKey", "PUT", request)
    }

    private suspend fun mutate(
        serverId: String,
        path: String,
        method: String,
        request: ServerLabsMutationRequest,
    ): ServerLabsMutationOutcome {
        val response =
            runCatchingPreservingCancellation {
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = path,
                    method = method,
                    serverIdOverride = ServerId(serverId),
                    bodyText = encodeServerLabsMutationRequest(request),
                )
            }.getOrElse { return ServerLabsMutationOutcome.Failed(transportFailure()) }
        if (!response.isSuccessful) return ServerLabsMutationOutcome.Failed(response.failure())
        val result = parseServerLabsMutation(response.bodyText) ?: return ServerLabsMutationOutcome.Failed(unreadable(response))
        return ServerLabsMutationOutcome.Done(result)
    }

    private fun HomeNetworkResponse.failure(): ServerLabsFailure {
        val body = parseServerLabsError(errorBodyText ?: bodyText)
        return ServerLabsFailure(
            ScreenError(
                code = if (code == 0) "network" else "http_$code",
                serverMessage = body?.message,
                serverCode = body?.code,
                traceId = traceId ?: build.raft.app.network.RaftFailedRequestTraceIds.recent(),
            ),
        )
    }

    private fun unreadable(response: HomeNetworkResponse): ServerLabsFailure =
        ServerLabsFailure(ScreenError(code = "server_labs_unreadable", retryable = false, traceId = response.traceId))

    private fun transportFailure(): ServerLabsFailure = ServerLabsFailure(ScreenError(code = "network", traceId = null))
}
