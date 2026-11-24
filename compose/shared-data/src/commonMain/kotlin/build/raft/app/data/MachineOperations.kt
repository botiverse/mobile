package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.reportUnknownTypeDropped
import build.raft.app.strictProjectionDiagnosticsLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/*
 * Requests that wait on a Computer longer than the edge in front of the API allows (CloudFront
 * closes a request whose origin sends nothing for 30 s): botiverse/slock #14842 / #14843
 * (#otter-lounge:#1), at slock ae2c99ce1aac7b495e8c0646aa67d41862a90572.
 *
 * - packages/shared/src/machineOperation.ts: `MachineOperationStarted = {operationId}` (a UUID),
 *   `MachineOperationView = {state:"pending"} | {state:"done", status, body}`,
 *   `MACHINE_OPERATION_POLL_MS = 1_500`.
 * - packages/server/src/routes/runtimeAuthRoutes.ts `answerMachineWait`: a request whose JSON body
 *   carries `respondAsync: true` gets 202 `{operationId}` once its own checks pass (a refusal still
 *   answers at once); `GET /api/servers/:id/machine-operations/:operationId` answers the view, or
 *   404 `operation_not_found` (unknown, expired, another person's, another replica's).
 * - Used by runtime sign-out (`…/runtime-auth/:runtime/logout`), a limit-reset redeem
 *   (`…/runtime-account-usage/:provider/limit-reset`, Codex and Claude) and the Claude limit-reset
 *   read (`…/claude/limit-resets/read`).
 * - packages/web/src/utils/runtimeAuthClient.ts `postWaitingOnComputer`: always asks; an answer
 *   without a valid `operationId` is the direct answer (an older server ignores the flag and
 *   answers synchronously, as before); reads every 1.5 s until the route's relay budget plus 10 s;
 *   a 404 read stops (the outcome is never learned), any other failed read is retried; no answer in
 *   time is an outcome never learned, as a cut request was.
 */

/** slock `MACHINE_OPERATION_POLL_MS`: how often a pending operation is read. */
@RaftCoreInternalApi const val MACHINE_OPERATION_POLL_MILLIS = 1_500L

/** web `MACHINE_OPERATION_SLACK_MS`: past the server's relay budget, for the last poll and transport. */
@RaftCoreInternalApi const val MACHINE_OPERATION_SLACK_MILLIS = 10_000L

/** slock `RUNTIME_AUTH_LOGOUT_RELAY_BUDGET_MS`. */
@RaftCoreInternalApi const val RUNTIME_AUTH_LOGOUT_RELAY_BUDGET_MILLIS = 45_000L

/** slock `RUNTIME_LIMIT_RESET_RELAY_BUDGET_MS` (Codex). */
@RaftCoreInternalApi const val RUNTIME_LIMIT_RESET_RELAY_BUDGET_MILLIS = 50_000L

/** slock `RUNTIME_LIMIT_RESET_CLAUDE_RELAY_BUDGET_MS` (a Claude redeem or read). */
@RaftCoreInternalApi const val RUNTIME_LIMIT_RESET_CLAUDE_RELAY_BUDGET_MILLIS = 120_000L

private val machineOperationJson = Json { ignoreUnknownKeys = true }

private val MachineOperationIdShape = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/** slock `asMachineOperationId`: only a lowercase UUID is an operation id. */
@RaftCoreInternalApi fun isMachineOperationId(value: String): Boolean = MachineOperationIdShape.matches(value)

/** The 202 body: `MachineOperationStarted`. */
@Serializable
private data class MachineOperationStartedDto(val operationId: String? = null)

/** `MachineOperationView` on the wire; `status` and `body` only when `state` is `done`. */
@Serializable
private data class MachineOperationViewDto(val state: String? = null, val status: Int? = null, val body: JsonElement? = null)

/** What a read of a machine operation said. */
@RaftCoreInternalApi sealed interface MachineOperationView {
    data object Pending : MachineOperationView

    /** The HTTP status and JSON body the request would have answered. */
    data class Done(val status: Int, val body: JsonElement) : MachineOperationView
}

/** The operation id of a 2xx answer that started one; null for any other answer (the direct one). */
@RaftCoreInternalApi fun HomeNetworkResponse.machineOperationIdOrNull(): String? {
    if (!isSuccessful) return null
    val text = bodyText?.takeIf { it.isNotBlank() } ?: return null
    val dto = runCatching { machineOperationJson.decodeFromString(MachineOperationStartedDto.serializer(), text) }.getOrNull()
    return dto?.operationId?.takeIf(::isMachineOperationId)
}

/**
 * A `GET …/machine-operations/:operationId` body. `pending`, and a state this app does not know,
 * read as still waiting (web: anything but `done` is read again); null when it is not a view.
 */
@RaftCoreInternalApi fun parseMachineOperationView(
    text: String?,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): MachineOperationView? {
    val body = text?.takeIf { it.isNotBlank() } ?: return null
    val dto = runCatching { machineOperationJson.decodeFromString(MachineOperationViewDto.serializer(), body) }.getOrNull() ?: return null
    return when (dto.state) {
        "pending" -> MachineOperationView.Pending
        "done" -> {
            val status = dto.status ?: return null
            MachineOperationView.Done(status = status, body = dto.body ?: return null)
        }
        null -> null
        else -> {
            reportUnknownTypeDropped("machine_operation", "state", dto.state, fallback = "pending", diagnostics = diagnostics)
            MachineOperationView.Pending
        }
    }
}

/**
 * Turns [started] into the answer the request would have had (web `postWaitingOnComputer`).
 *
 * - No operation id in it: [started] is the answer (a refusal, or an older server's direct reply).
 * - Otherwise [read] is called every [MACHINE_OPERATION_POLL_MILLIS] until the operation is done,
 *   then its status and body come back as a [HomeNetworkResponse] the route's own parser reads. The
 *   answer keeps [started]'s headers: the `X-Trace-Id` of the request whose trace holds the work.
 * - A 404 read, or nothing done within [budgetMillis] + [MACHINE_OPERATION_SLACK_MILLIS]: an
 *   outcome never learned, code 0 like a request that got no answer (the trace id is kept).
 * - Cancelling the caller (the page left) stops the reads; the server keeps the answer for 10 min.
 */
@RaftCoreInternalApi suspend fun awaitMachineOperation(
    started: HomeNetworkResponse,
    budgetMillis: Long,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
    read: suspend (operationId: String) -> HomeNetworkResponse,
): HomeNetworkResponse {
    val operationId = started.machineOperationIdOrNull() ?: return started
    val outcome = withTimeoutOrNull(budgetMillis + MACHINE_OPERATION_SLACK_MILLIS) { pollMachineOperation(operationId, diagnostics, read) }
    if (outcome == null) {
        diagnostics("machine_operation_unanswered")
        return HomeNetworkResponse(code = 0, bodyText = null, responseHeaders = started.responseHeaders)
    }
    val body = outcome.body.toString()
    return if (outcome.status in 200..299) {
        HomeNetworkResponse(code = outcome.status, bodyText = body, responseHeaders = started.responseHeaders)
    } else {
        HomeNetworkResponse(code = outcome.status, bodyText = null, errorBodyText = body, responseHeaders = started.responseHeaders)
    }
}

/** Reads until the operation is done; null when it is gone (404). */
private suspend fun pollMachineOperation(
    operationId: String,
    diagnostics: (String) -> Unit,
    read: suspend (operationId: String) -> HomeNetworkResponse,
): MachineOperationView.Done? {
    while (true) {
        delay(MACHINE_OPERATION_POLL_MILLIS)
        val response =
            try {
                read(operationId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                continue
            }
        // Gone (expired, another replica's restart): never learned. A blip: read again.
        if (response.code == 404) return null
        if (!response.isSuccessful) continue
        val view = parseMachineOperationView(response.bodyText, diagnostics)
        if (view is MachineOperationView.Done) return view
    }
}
