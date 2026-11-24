package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.network.RaftFailedRequestTraceIds
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val integrationsActionsPushJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

/**
 * Typed Integrations/Actions/Push boundary.
 *
 * Server contracts:
 *  - `packages/server/src/services/oauthService.ts` (OAuthClient,
 *    GrantSummary, RequestSummary shapes).
 *  - `packages/server/src/routes/integrations.ts` (per-endpoint payloads).
 *  - `packages/server/src/routes/actions.ts`, `routes/push.ts`.
 *
 * Field names are exact camelCase. No snake_case fallbacks, no speculative
 * aliases like `secret`/`key`/`state`.
 *
 * Step-up: approving an access request (`POST /integrations/requests/:requestId/approve`, slock
 * staging 0dd599ab68 #12297, routes/integrations.ts `refuseUnverifiedApprover`) answers
 * `403 { error: "Confirm it's you with your second step before approving this app's access.",
 * code: "second_factor_step_up_required", methods }` for a person with two-step verification on
 * whose sign-in has not passed it. An `integration:approve_agent_login` card's
 * `POST /actions/:messageId/execute` is not gated at that SHA (pending contract verification);
 * its error carries the same challenge if it comes. Both as [ScreenError.secondFactorStepUp].
 * Deny is not gated.
 *
 * Revoke (botiverse/slock staging 5828cdce3e, routes/integrations.ts `POST /grants/:grantId/revoke`;
 * web AgentAppAccessTab `confirmRevoke`, #13293): `POST /api/integrations/grants/:grantId/revoke`
 * with `{}` (the route reads no field). One grant per call; never gated by a second step. 200: the
 * grant row (already revoked: the same row again). 403 `{ error: "Only server owners and admins can
 * manage integrations", code: "insufficient_role" }` for someone who is neither the agent's creator
 * nor has `manageExternalAuth`. 404 `{ error: "Grant not found", code: "not_found" }` for a grant
 * the server doesn't have (another server's included). 500 `{ error: "Failed to revoke integration
 * grant", correlationId }`. See [toAgentAppGrantRevokeOutcome].
 */
internal interface RaftIntegrationsActionsPushApi {
    suspend fun integrationsOverview(): HomeNetworkResponse
    suspend fun integrationClients(): HomeNetworkResponse
    suspend fun createIntegrationClient(body: String): HomeNetworkResponse
    suspend fun approveIntegrationRequest(requestId: String, body: String): HomeNetworkResponse
    suspend fun denyIntegrationRequest(requestId: String): HomeNetworkResponse
    suspend fun revokeIntegrationGrant(grantId: String): HomeNetworkResponse
    suspend fun agentIntegrations(agentId: MemberId): HomeNetworkResponse

    /** `GET /api/integrations/agents/:agentId/events` (`clientId`, `before` optional). */
    suspend fun agentAppEvents(agentId: MemberId, clientId: String?, before: String?): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)

    /** `GET /api/integrations/agents/:agentId/events/:eventId`. */
    suspend fun agentAppEvent(agentId: MemberId, eventId: String): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)

    suspend fun executeAction(messageId: MessageId, body: String): HomeNetworkResponse
    /** `POST /api/actions/:messageId/close` (botiverse/slock #16473): answerer closes a question card without answering. */
    suspend fun closeQuestionAction(messageId: MessageId): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
    /** `GET /api/actions/:messageId/seat-request` (botiverse/slock #16449, #16453): check seats short & server pricing. */
    suspend fun checkSeatRequest(messageId: MessageId, serverIdOverride: ServerId? = null): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
    /** `POST /api/actions/:messageId/seats-added` (botiverse/slock #16449, #16453): complete a seat request once seats cover it. */
    suspend fun completeSeatRequest(messageId: MessageId, serverIdOverride: ServerId? = null): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
    suspend fun emitActionEvent(messageId: MessageId, body: String): HomeNetworkResponse
    suspend fun markActionExecuted(messageId: MessageId, body: String): HomeNetworkResponse
    suspend fun reconfirmAction(messageId: MessageId): HomeNetworkResponse
    suspend fun pushVapidKey(): HomeNetworkResponse
    suspend fun recordPushPromptEvent(body: String): HomeNetworkResponse
    suspend fun subscribePush(body: String): HomeNetworkResponse
    suspend fun unsubscribePush(body: String): HomeNetworkResponse
    suspend fun testPush(): HomeNetworkResponse
}

internal class RaftIntegrationsActionsPushNetworkApi(
    private val networkClient: RaftNetworkClient
) : RaftIntegrationsActionsPushApi {
    override suspend fun integrationsOverview(): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Visible, "integrations/overview")

    override suspend fun integrationClients(): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Visible, "integrations/clients")

    override suspend fun createIntegrationClient(body: String): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Foreground, "integrations/clients", method = "POST", bodyText = body)

    override suspend fun approveIntegrationRequest(requestId: String, body: String): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Foreground, "integrations/requests/$requestId/approve", method = "POST", bodyText = body)

    override suspend fun denyIntegrationRequest(requestId: String): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Foreground, "integrations/requests/$requestId/deny", method = "POST")

    override suspend fun revokeIntegrationGrant(grantId: String): HomeNetworkResponse =
        networkClient.request(
            DataRequestPriority.Foreground,
            "integrations/grants/$grantId/revoke",
            method = "POST",
            bodyText = REVOKE_INTEGRATION_GRANT_BODY
        )

    override suspend fun agentIntegrations(agentId: MemberId): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Visible, "integrations/agents/${agentId.value}")

    override suspend fun agentAppEvents(agentId: MemberId, clientId: String?, before: String?): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "integrations/agents/${agentId.value}/events",
            query = listOfNotNull(clientId?.let { "clientId" to it }, before?.let { "before" to it })
        )

    override suspend fun agentAppEvent(agentId: MemberId, eventId: String): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Visible, "integrations/agents/${agentId.value}/events/$eventId")

    override suspend fun executeAction(messageId: MessageId, body: String): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Foreground, "actions/${messageId.value}/execute", method = "POST", bodyText = body)

    override suspend fun closeQuestionAction(messageId: MessageId): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Foreground, "actions/${messageId.value}/close", method = "POST")

    override suspend fun checkSeatRequest(messageId: MessageId, serverIdOverride: ServerId?): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "actions/${messageId.value}/seat-request",
            serverIdOverride = serverIdOverride,
        )

    override suspend fun completeSeatRequest(messageId: MessageId, serverIdOverride: ServerId?): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "actions/${messageId.value}/seats-added",
            method = "POST",
            bodyText = "{}",
            serverIdOverride = serverIdOverride,
        )

    override suspend fun emitActionEvent(messageId: MessageId, body: String): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Background, "actions/${messageId.value}/event", method = "POST", bodyText = body)

    override suspend fun markActionExecuted(messageId: MessageId, body: String): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Foreground, "actions/${messageId.value}/mark-executed", method = "POST", bodyText = body)

    override suspend fun reconfirmAction(messageId: MessageId): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Foreground, "actions/${messageId.value}/reconfirm", method = "POST", bodyText = "{}")

    override suspend fun pushVapidKey(): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Visible, "push/vapid-key")

    override suspend fun recordPushPromptEvent(body: String): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Background, "push/prompt-events", method = "POST", bodyText = body)

    override suspend fun subscribePush(body: String): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Foreground, "push/subscribe", method = "POST", bodyText = body)

    override suspend fun unsubscribePush(body: String): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Foreground, "push/subscribe", method = "DELETE", bodyText = body)

    override suspend fun testPush(): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Foreground, "push/test", method = "POST")
}

internal class RaftIntegrationsActionsPushApiClient(
    private val transport: RaftIntegrationsActionsPushApi
) {
    suspend fun integrationsOverview(): DataResult<IntegrationsOverviewPage> =
        transport.integrationsOverview().mapApiBody(
            "integrations_overview_contract_mismatch",
            ListSerializer(IntegrationOverviewItemDto.serializer())
        ) { wire ->
            IntegrationsOverviewPage(itemCount = wire.size, firstClientName = wire.firstOrNull()?.clientName)
        }

    suspend fun integrationClients(): DataResult<IntegrationClientsPage> =
        transport.integrationClients().mapApiBody(
            "integration_clients_contract_mismatch",
            ListSerializer(IntegrationClientDto.serializer())
        ) { wire ->
            IntegrationClientsPage(clientCount = wire.size, firstName = wire.firstOrNull()?.name)
        }

    suspend fun createIntegrationClient(body: String): DataResult<IntegrationClientCreateResult> =
        transport.createIntegrationClient(body).mapApiBody(
            "integration_client_create_contract_mismatch",
            CreateIntegrationClientResponseDto.serializer()
        ) { wire ->
            val id = wire.client.id.takeIf { it.isNotBlank() } ?: return@mapApiBody null
            IntegrationClientCreateResult(
                clientId = id,
                clientSecretPresent = !wire.clientSecret.isNullOrBlank()
            )
        }

    suspend fun approveIntegrationRequest(requestId: String, body: String): DataResult<IntegrationApproveResult> =
        transport.approveIntegrationRequest(requestId, body).mapApiBody(
            "integration_request_approve_contract_mismatch",
            ApproveIntegrationResponseDto.serializer()
        ) { wire ->
            val id = wire.request.id.takeIf { it.isNotBlank() } ?: return@mapApiBody null
            IntegrationApproveResult(requestId = id, grantId = wire.grantId)
        }

    suspend fun denyIntegrationRequest(requestId: String): DataResult<IntegrationRequestResult> =
        transport.denyIntegrationRequest(requestId).mapApiBody(
            "integration_request_deny_contract_mismatch",
            IntegrationRequestDto.serializer()
        ) { wire ->
            val id = wire.id.takeIf { it.isNotBlank() } ?: return@mapApiBody null
            val status = wire.status?.takeIf { it.isNotBlank() } ?: return@mapApiBody null
            IntegrationRequestResult(requestId = id, status = status)
        }

    /** One grant's revoke; what the answer means for the Apps tab is [toAgentAppGrantRevokeOutcome]. */
    suspend fun revokeIntegrationGrant(grantId: String): AgentAppGrantRevokeOutcome =
        transport.revokeIntegrationGrant(grantId).toAgentAppGrantRevokeOutcome()

    suspend fun agentIntegrations(agentId: MemberId): DataResult<AgentIntegrationsPage> =
        transport.agentIntegrations(agentId).mapAgentIntegrations(agentId)

    /** The events apps sent to an agent, newest first ([AgentAppEventPage.nextCursor]: older ones). */
    suspend fun agentAppEvents(agentId: MemberId, clientId: String?, before: String?): DataResult<AgentAppEventPage> {
        val response = transport.agentAppEvents(agentId, clientId, before)
        if (!response.isSuccessful) return response.agentIntegrationsFailure()
        return response.mapApiBody("agent_app_events_contract_mismatch", AgentAppEventListDto.serializer()) { wire ->
            AgentAppEventPage(
                events = wire.events.map { it.toAgentAppEvent() ?: return@mapApiBody null },
                nextCursor = wire.nextCursor?.takeIf { it.isNotBlank() }
            )
        }
    }

    /** One event with its payload (app-supplied JSON, shown as inert text). */
    suspend fun agentAppEvent(agentId: MemberId, eventId: String): DataResult<AgentAppEventDetail> {
        val response = transport.agentAppEvent(agentId, eventId)
        if (!response.isSuccessful) return response.agentIntegrationsFailure()
        return response.mapApiBody("agent_app_event_contract_mismatch", AgentAppEventDetailDto.serializer()) { wire ->
            AgentAppEventDetail(
                eventId = wire.id.takeIf { it.isNotBlank() } ?: return@mapApiBody null,
                payloadText = agentAppEventPayloadJson.encodeToString(JsonElement.serializer(), wire.payload ?: JsonObject(emptyMap()))
            )
        }
    }

    suspend fun executeAction(messageId: MessageId, body: String): DataResult<ActionCardResult> =
        transport.executeAction(messageId, body).mapActionCard("action_execute_contract_mismatch")

    /**
     * POST /api/actions/:messageId/execute from a spec card (`action-card.v1`): a plain execute,
     * or with a form (`form.v1`). The form refusals the contract names come back as content so the
     * sheet can show them: 422 `FORM_INVALID` with its issues, 422 `FORM_REQUIRED`, and 409
     * `ALREADY_EXECUTED` with the card as it now is. Anything else is an error as [executeAction].
     */
    suspend fun executeActionCardSpec(messageId: MessageId, body: String): DataResult<ActionCardExecuteOutcome> {
        val response = transport.executeAction(messageId, body)
        if (!response.isSuccessful) {
            response.actionCardFormRefusalOrNull()?.let { return DataResult.Content(it) }
        }
        return when (val result = response.mapActionCard("action_execute_contract_mismatch")) {
            is DataResult.Content -> DataResult.Content(ActionCardExecuteOutcome.Executed(result.data.actionCard))
            is DataResult.Empty -> result
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    /**
     * POST /api/actions/:messageId/close (botiverse/slock #16473, routes/actions.ts): a person who
     * may answer a `question:ask` card closes it without answering. 200 carries `{ messageId,
     * metadata }` with the closed card (`state: "expired"` + `closedBy`/`closedAt`, or `state:
     * "closed"`); 409 `ALREADY_EXECUTED` carries the answered card in `metadata`; any other refusal
     * stays an error with the server's message, code and trace id.
     */
    suspend fun closeQuestionActionCard(messageId: MessageId): DataResult<ActionCardCloseOutcome> {
        val response = transport.closeQuestionAction(messageId)
        if (!response.isSuccessful) {
            val already = response.actionCardFormRefusalOrNull() as? ActionCardExecuteOutcome.AlreadyExecuted
            if (already != null) {
                return DataResult.Content(ActionCardCloseOutcome.AlreadyExecuted(already.card, already.serverMessage))
            }
        }
        return when (val result = response.mapActionCard("action_close_contract_mismatch")) {
            is DataResult.Content -> DataResult.Content(ActionCardCloseOutcome.Closed(result.data.actionCard))
            is DataResult.Empty -> result
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    /**
     * GET /api/actions/:messageId/seat-request (botiverse/slock #16449, #16453): checks whether
     * the requester can still act, how many seats are short now, and the card's server's pricing.
     * Older servers without the route (404 without a structured action `errorCode`) resolve to
     * [SeatRequestCheckOutcome.Unsupported] so the app falls back to opening the message on the web;
     * older servers that omit `pricing` default to [ProPricing.SeatV1].
     */
    suspend fun checkSeatRequest(
        messageId: MessageId,
        serverIdOverride: ServerId? = null,
    ): DataResult<SeatRequestCheckOutcome> {
        val response = transport.checkSeatRequest(messageId, serverIdOverride)
        if (!response.isSuccessful) {
            if (response.code == 404 && response.actionErrorEnvelopeOrNull()?.errorCode == null) {
                return DataResult.Content(SeatRequestCheckOutcome.Unsupported)
            }
            return response.mapActionError("action_seat_request_contract_mismatch")
        }
        return response.mapApiBody("action_seat_request_contract_mismatch", SeatRequestCheckResponseDto.serializer()) { wire ->
            val parsedPricing = ProPricing.fromWireOrNull(wire.pricing)
            SeatRequestCheckOutcome.Checked(
                SeatRequestCheck(
                    requesterAllowed = wire.requesterAllowed,
                    seatsShort = wire.seatsShort,
                    pricing = billingPricing(parsedPricing),
                    pricingExplicit = parsedPricing != null,
                )
            )
        }
    }

    /**
     * POST /api/actions/:messageId/seats-added (botiverse/slock #16449, #16453): completes a
     * `seats:add` card when no seats are short (including agents under `person_v2`) or the
     * requester can no longer act.
     */
    suspend fun completeSeatRequest(
        messageId: MessageId,
        serverIdOverride: ServerId? = null,
    ): DataResult<SeatRequestCompleteOutcome> =
        when (val result = transport.completeSeatRequest(messageId, serverIdOverride).mapActionCard("action_seats_added_contract_mismatch")) {
            is DataResult.Content -> DataResult.Content(SeatRequestCompleteOutcome.Completed(result.data.actionCard))
            is DataResult.Empty -> result
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }

    suspend fun emitActionEvent(messageId: MessageId, body: String): DataResult<ActionEventResult> =
        transport.emitActionEvent(messageId, body).mapActionEvent("action_event_contract_mismatch")

    suspend fun markActionExecuted(messageId: MessageId, body: String): DataResult<ActionCardResult> =
        transport.markActionExecuted(messageId, body).mapActionCard("action_mark_executed_contract_mismatch")

    /**
     * POST /api/actions/:messageId/reconfirm (botiverse/slock routes/actions.ts): a
     * `reconfirm_required` card back to `prepared` under a new confirmation version. The
     * response is `{ messageId, metadata, confirmationVersion }`.
     */
    suspend fun reconfirmAction(messageId: MessageId): DataResult<ActionCardResult> =
        transport.reconfirmAction(messageId).mapActionCard("action_reconfirm_contract_mismatch")

    suspend fun pushVapidKey(): DataResult<PushVapidKey> =
        transport.pushVapidKey().mapApiBody(
            "push_vapid_key_contract_mismatch",
            PushVapidKeyResponseDto.serializer()
        ) { wire ->
            val key = wire.publicKey.takeIf { it.isNotBlank() } ?: return@mapApiBody null
            PushVapidKey(publicKey = key)
        }

    suspend fun recordPushPromptEvent(body: String): DataResult<PushCommandResult> =
        transport.recordPushPromptEvent(body).mapPushCommand("push_prompt_event_contract_mismatch")

    suspend fun subscribePush(body: String): DataResult<PushCommandResult> =
        transport.subscribePush(body).mapPushCommand("push_subscribe_contract_mismatch")

    suspend fun unsubscribePush(body: String): DataResult<PushCommandResult> =
        transport.unsubscribePush(body).mapPushCommand("push_unsubscribe_contract_mismatch")

    suspend fun testPush(): DataResult<PushTestResult> =
        transport.testPush().mapApiBody(
            "push_test_contract_mismatch",
            PushTestResponseDto.serializer()
        ) { wire ->
            if (!wire.ok) return@mapApiBody null
            val attempted = wire.attempted ?: return@mapApiBody null
            val delivered = wire.delivered ?: return@mapApiBody null
            val failed = wire.failed ?: return@mapApiBody null
            PushTestResult(
                ok = true,
                attempted = attempted,
                delivered = delivered,
                failed = failed,
                stale = wire.stale,
            )
        }
}

internal data class IntegrationsOverviewPage(val itemCount: Int, val firstClientName: String?)
internal data class IntegrationClientsPage(val clientCount: Int, val firstName: String?)
internal data class IntegrationClientCreateResult(val clientId: String, val clientSecretPresent: Boolean)
internal data class IntegrationApproveResult(val requestId: String, val grantId: String?)
internal data class IntegrationRequestResult(val requestId: String, val status: String)

/**
 * What one grant's revoke came to (web AgentAppAccessTab `confirmRevoke`, botiverse/slock #13293):
 * it went, it was gone already, or it still stands and [NotRevoked.failure] says why.
 */
sealed interface AgentAppGrantRevokeOutcome {
    /** 2xx: revoked (or it already was). The web reads nothing of the body, so neither does this. */
    data object Revoked : AgentAppGrantRevokeOutcome

    /**
     * 404 "Grant not found": the server has nothing left to revoke, so the grant leaves the list as
     * a revoked one does (a Try again could only 404 again).
     */
    data object Gone : AgentAppGrantRevokeOutcome

    data class NotRevoked(val failure: AgentAppGrantRevokeFailure) : AgentAppGrantRevokeOutcome
}

/**
 * Why one grant's revoke failed (web `agentAppGrants.ts` `RevokeGrantFailure`). [reason] is the
 * answer's `error` when it had one; [traceId] its `X-Trace-Id` (AGENTS.md "Errors carry the trace ID").
 */
sealed interface AgentAppGrantRevokeFailure {
    /** 403: the server said no, so trying again won't help; Try again skips it. */
    data class Refused(val reason: String?, val traceId: String?) : AgentAppGrantRevokeFailure

    /** The server answered with another failure (5xx, 401, ...). */
    data class Failed(val reason: String?, val traceId: String?) : AgentAppGrantRevokeFailure

    /** No answer at all: the write may or may not have landed (the reload tells). */
    data object NoAnswer : AgentAppGrantRevokeFailure
}

/**
 * The answer to `POST /api/integrations/grants/:grantId/revoke` by its status, as the web reads
 * its `api.post` result (`revokeGrantFailure`): any 2xx revoked, 404 gone, 403 refused, no
 * response (code 0, a transport failure or timeout) unknown, anything else a failure; the reason
 * is the envelope's `error` (`refuse` and `sendJsonServerError` both write it).
 */
internal fun HomeNetworkResponse.toAgentAppGrantRevokeOutcome(): AgentAppGrantRevokeOutcome =
    when {
        isSuccessful -> AgentAppGrantRevokeOutcome.Revoked
        code <= 0 -> AgentAppGrantRevokeOutcome.NotRevoked(AgentAppGrantRevokeFailure.NoAnswer)
        code == 404 -> AgentAppGrantRevokeOutcome.Gone
        code == 403 ->
            AgentAppGrantRevokeOutcome.NotRevoked(
                AgentAppGrantRevokeFailure.Refused(reason = agentIntegrationsServerErrorTextOrNull(), traceId = traceId)
            )
        else ->
            AgentAppGrantRevokeOutcome.NotRevoked(
                AgentAppGrantRevokeFailure.Failed(reason = agentIntegrationsServerErrorTextOrNull(), traceId = traceId)
            )
    }

internal data class AgentIntegrationsPage(
    val agentId: MemberId,
    val items: List<AgentAppLoginItem>
) {
    fun toSnapshot(): AgentAppLoginSnapshot =
        AgentAppLoginSnapshot(
            agentId = agentId,
            items = items,
            loadState = if (items.isEmpty()) AgentAppLoginLoadState.Empty else AgentAppLoginLoadState.Content
        )
}
internal data class ActionCardResult(
    val messageId: String,
    val metadataPresent: Boolean,
    val actionCard: ThreadActionCard? = null
)
/** What an execute from a spec card came to ([RaftIntegrationsActionsPushApiClient.executeActionCardSpec]). */
internal sealed interface ActionCardExecuteOutcome {
    /** 200: run (or the same person's same values again); [card] is the card the answer carries. */
    data class Executed(val card: ThreadActionCard?) : ActionCardExecuteOutcome

    /**
     * 422 `FORM_INVALID`: what is wrong, under a field or (field null) for the whole form. [traceId]
     * is the refusal's `X-Trace-Id` (AGENTS.md "Errors carry the trace ID"), shown once under the issues.
     */
    data class FormInvalid(val issues: List<ActionCardFormIssue>, val serverMessage: String?, val traceId: String? = null) : ActionCardExecuteOutcome

    /** 422 `FORM_REQUIRED`: the card takes a form this request did not send (the card changed). */
    data class FormRequired(val serverMessage: String?) : ActionCardExecuteOutcome

    /** 409 `ALREADY_EXECUTED`: someone ran the card first; [card] is the card as it now is. */
    data class AlreadyExecuted(val card: ThreadActionCard?, val serverMessage: String?) : ActionCardExecuteOutcome
}

/** What `POST /api/actions/:messageId/close` came to ([RaftIntegrationsActionsPushApiClient.closeQuestionActionCard], slock #16473). */
internal sealed interface ActionCardCloseOutcome {
    /** 200: closed without answering; [card] is the closed card returned by the server. */
    data class Closed(val card: ThreadActionCard?) : ActionCardCloseOutcome

    /** 409 `ALREADY_EXECUTED`: answered first; [card] is the answered card returned by the server. */
    data class AlreadyExecuted(val card: ThreadActionCard?, val serverMessage: String?) : ActionCardCloseOutcome
}

/** What `GET /api/actions/:messageId/seat-request` came to ([RaftIntegrationsActionsPushApiClient.checkSeatRequest], slock #16449, #16453). */
internal sealed interface SeatRequestCheckOutcome {
    data class Checked(val check: SeatRequestCheck) : SeatRequestCheckOutcome

    /** Older server without `GET /api/actions/:messageId/seat-request`: fall back to opening on the web. */
    data object Unsupported : SeatRequestCheckOutcome
}

/** What `POST /api/actions/:messageId/seats-added` came to ([RaftIntegrationsActionsPushApiClient.completeSeatRequest], slock #16449, #16453). */
internal sealed interface SeatRequestCompleteOutcome {
    val card: ThreadActionCard?

    data class Completed(override val card: ThreadActionCard?) : SeatRequestCompleteOutcome
}

internal data class ActionEventResult(val ok: Boolean)
internal data class PushVapidKey(val publicKey: String)
internal data class PushCommandResult(val ok: Boolean)
internal data class PushTestResult(val ok: Boolean, val attempted: Int, val delivered: Int, val failed: Int, val stale: Int?)

private inline fun <Wire, T> HomeNetworkResponse.mapApiBody(
    contractCode: String,
    serializer: KSerializer<Wire>,
    crossinline transform: (Wire) -> T?,
): DataResult<T> {
    if (!isSuccessful) {
        return DataResult.Error(
            ScreenError(
                code = contractCode,
                retryable = code >= 500,
                // Approving an access request with two-step verification on (SecondFactorStepUpApi.kt).
                secondFactorStepUp = secondFactorStepUpChallengeOrNull(),
            )
        )
    }
    val body = bodyText?.takeIf { it.isNotBlank() }
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    val wire = try {
        integrationsActionsPushJson.decodeFromString(serializer, body)
    } catch (_: SerializationException) {
        return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    }
    val value = transform(wire)
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    return DataResult.Content(value)
}

private fun HomeNetworkResponse.mapActionError(contractCode: String): DataResult<Nothing> {
    // routes/actions.ts `handleError` answers a refusal with `{ error, errorCode }`; keep both so a caller can
    // name a refusal no retry fixes (an install card's 409 `APP_PERMISSIONS_CHANGED`, ...).
    val envelope = actionErrorEnvelopeOrNull()
    val serverMessage = envelope?.error?.takeIf { it.isNotBlank() }
    if (code == 401) {
        // An expired session: the route signs out like every other card path.
        return DataResult.NoPermission(
            PermissionBlocker(
                code = "auth_required",
                requiredCapability = "auth_session",
                serverMessage = serverMessage,
                traceId = traceId ?: RaftFailedRequestTraceIds.recent(),
            )
        )
    }
    return DataResult.Error(
        ScreenError(
            code = contractCode,
            retryable = code >= 500,
            serverMessage = serverMessage,
            // The feedback limiter's 429 (shared with POST /api/product-feedback) names it `code`
            // (`feedback_rate_limited`); everything else under handleError says `errorCode`.
            serverCode = (envelope?.errorCode?.takeIf { it.isNotBlank() } ?: envelope?.code?.takeIf { it.isNotBlank() }),
            retryAfterSeconds = if (code == 429) retryAfterHeaderSeconds() else null,
            serverFields =
                buildMap {
                    // An install refused for a newer prompt-text revision (web appTextChangedCurrent).
                    envelope?.currentTextRevisionId?.takeIf { it.isNotBlank() }?.let { put("current_text_revision_id", it) }
                },
            // An Approve Login card's execute with two-step verification on: the caller steps
            // up and runs it once more (SecondFactorStepUpCoordinator).
            secondFactorStepUp = secondFactorStepUpChallengeOrNull(),
            traceId = traceId ?: RaftFailedRequestTraceIds.recent(),
        )
    )
}

private fun HomeNetworkResponse.mapActionCard(contractCode: String): DataResult<ActionCardResult> {
    if (!isSuccessful) return mapActionError(contractCode)
    return mapApiBody(contractCode, ActionCardResponseDto.serializer()) { wire ->
        val id = wire.messageId.takeIf { it.isNotBlank() } ?: return@mapApiBody null
        val card = wire.metadata.toThreadActionCard()
        ActionCardResult(messageId = id, metadataPresent = wire.metadata != null, actionCard = card)
    }
}

/** The form refusals of `routes/actions.ts` `handleError` (shared `ActionCardFormInvalidBody` and kin), else null. */
private fun HomeNetworkResponse.actionCardFormRefusalOrNull(): ActionCardExecuteOutcome? {
    if (code != 422 && code != 409) return null
    val payload = errorBodyText?.takeIf { it.isNotBlank() } ?: bodyText?.takeIf { it.isNotBlank() } ?: return null
    val envelope =
        try {
            integrationsActionsPushJson.decodeFromString(ActionCardFormErrorEnvelopeDto.serializer(), payload)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
    val message = envelope.error?.takeIf { it.isNotBlank() }
    return when {
        code == 422 && envelope.errorCode == "FORM_INVALID" ->
            ActionCardExecuteOutcome.FormInvalid(envelope.issues.orEmpty().map { it.toActionCardFormIssue() }, message, traceId)
        code == 422 && envelope.errorCode == "FORM_REQUIRED" -> ActionCardExecuteOutcome.FormRequired(message)
        code == 409 && envelope.errorCode == "ALREADY_EXECUTED" ->
            ActionCardExecuteOutcome.AlreadyExecuted(envelope.metadata.toThreadActionCard(), message)
        else -> null
    }
}

/**
 * One issue as the server sent it. A code this build does not know reads as `invalid_format`; a
 * message it cannot read is the generic one for the code (both keep the issue on its field).
 */
private fun ActionCardFormIssueDto.toActionCardFormIssue(): ActionCardFormIssue {
    val issueCode = ActionCardFormIssueCode.entries.firstOrNull { it.wire == code } ?: ActionCardFormIssueCode.InvalidFormat
    val msg = message?.let { parseActionCardSpecMsg(it) } ?: ACTION_CARD_FORM_ISSUE_MESSAGES.getValue(issueCode)
    return ActionCardFormIssue(field = field?.takeIf { it.isNotBlank() }, code = issueCode, message = msg)
}

private fun HomeNetworkResponse.actionErrorEnvelopeOrNull(): ActionErrorEnvelopeDto? {
    val payload = errorBodyText?.takeIf { it.isNotBlank() }
        ?: bodyText?.takeIf { it.isNotBlank() }
        ?: return null
    return runCatching {
        integrationsActionsPushJson.decodeFromString(ActionErrorEnvelopeDto.serializer(), payload)
    }.getOrNull()
}

private fun HomeNetworkResponse.mapPushCommand(contractCode: String): DataResult<PushCommandResult> =
    mapApiBody(contractCode, OkResponseDto.serializer()) { wire ->
        if (!wire.ok) null else PushCommandResult(ok = true)
    }

private fun HomeNetworkResponse.mapAgentIntegrations(agentId: MemberId): DataResult<AgentIntegrationsPage> {
    val contractCode = "agent_integrations_contract_mismatch"
    if (!isSuccessful) return agentIntegrationsFailure()
    return mapApiBody(
        contractCode,
        ListSerializer(IntegrationOverviewItemDto.serializer())
    ) { wire ->
        AgentIntegrationsPage(
            agentId = agentId,
            items = wire.map { it.toAgentAppLoginItem() ?: return@mapApiBody null }
        )
    }
}

/** A non-2xx answer from an `/api/integrations/agents/:agentId…` read: 401 / 403 / the rest. */
private fun HomeNetworkResponse.agentIntegrationsFailure(): DataResult<Nothing> {
    val serverMessage = agentIntegrationsServerErrorTextOrNull()
    return when (code) {
        401 ->
            DataResult.NoPermission(
                PermissionBlocker(
                    code = "auth_required",
                    requiredCapability = "auth_session",
                    serverMessage = serverMessage
                )
            )
        403 ->
            DataResult.NoPermission(
                PermissionBlocker(
                    code = "server_admin_required",
                    requiredCapability = "manage_integrations",
                    serverMessage = serverMessage
                )
            )
        else ->
            DataResult.Error(
                ScreenError(
                    code = if (code >= 500) "server_unavailable" else "http_$code",
                    retryable = code >= 500,
                    serverMessage = serverMessage
                )
            )
    }
}

/**
 * The `error` of a non-2xx `GET /api/integrations/agents/:agentId` or `POST
 * /api/integrations/grants/:grantId/revoke` (routes/integrations.ts: `refuse` and
 * `sendJsonServerError` both write `{ error, code? }`).
 */
private fun HomeNetworkResponse.agentIntegrationsServerErrorTextOrNull(): String? {
    val payload = bodyText?.takeIf { it.isNotBlank() }
        ?: errorBodyText?.takeIf { it.isNotBlank() }
        ?: return null
    return runCatching {
        integrationsActionsPushJson.decodeFromString(AgentIntegrationsErrorDto.serializer(), payload)
            .error
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

/**
 * Action event endpoints answer with HTTP 204 / empty body on success. An
 * empty body — or an empty JSON object — passes; any other shape is a contract
 * mismatch.
 */
private fun HomeNetworkResponse.mapActionEvent(contractCode: String): DataResult<ActionEventResult> {
    if (!isSuccessful) {
        return DataResult.Error(ScreenError(code = contractCode, retryable = code >= 500))
    }
    val raw = bodyText
    if (raw.isNullOrBlank()) {
        return DataResult.Content(ActionEventResult(ok = true))
    }
    val wire = try {
        integrationsActionsPushJson.decodeFromString(JsonObject.serializer(), raw)
    } catch (_: SerializationException) {
        return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    }
    return if (wire.isEmpty()) {
        DataResult.Content(ActionEventResult(ok = true))
    } else {
        DataResult.Error(ScreenError(code = contractCode, retryable = false))
    }
}

// ── Wire DTOs ──

/**
 * routes/actions.ts `handleError`: `{ error, errorCode }` (an `ActionCardError`'s status, code and
 * message). A feedback card's form submit over the per-person feedback limit gets the limiter's own
 * 429 `{ error, code: "feedback_rate_limited" }` plus `Retry-After` (botiverse/slock 7799cc436).
 */
@Serializable
private data class ActionErrorEnvelopeDto(
    val error: String? = null,
    val errorCode: String? = null,
    val code: String? = null,
    @SerialName("current_text_revision_id")
    val currentTextRevisionId: String? = null,
)

/** Shared `ActionCardFormInvalidBody` / `ActionCardFormRequiredBody` / `ActionCardAlreadyExecutedBody`. */
@Serializable
private data class ActionCardFormErrorEnvelopeDto(
    val error: String? = null,
    val errorCode: String? = null,
    val issues: List<ActionCardFormIssueDto>? = null,
    val metadata: WireActionCardMetadata? = null,
)

/** Shared `ActionCardFormIssue`: `{ field: string | null, code, message: Msg }`. */
@Serializable
private data class ActionCardFormIssueDto(
    val field: String? = null,
    val code: String,
    val message: JsonElement? = null,
)

/** slock shared `AgentAppEventListResponse`. */
@Serializable
private data class AgentAppEventListDto(
    val events: List<AgentAppEventDto> = emptyList(),
    val nextCursor: String? = null
)

/** slock shared `AgentAppEventSummary` (the fields the Apps tab shows). */
@Serializable
private data class AgentAppEventDto(
    val id: String = "",
    val app: AgentAppEventAppDto? = null,
    val kind: String = "",
    val summary: String = "",
    val status: String = "",
    val createdAt: String = ""
)

@Serializable
private data class AgentAppEventAppDto(
    val clientId: String = "",
    val name: String = ""
)

/** slock shared `AgentAppEventDetail`: the summary plus the app's `payload`. */
@Serializable
private data class AgentAppEventDetailDto(
    val id: String = "",
    val payload: JsonElement? = null
)

private fun AgentAppEventDto.toAgentAppEvent(): AgentAppEvent? {
    val app = app ?: return null
    if (id.isBlank() || app.clientId.isBlank()) return null
    return AgentAppEvent(
        id = id,
        appClientId = app.clientId,
        appName = app.name,
        kind = kind,
        summary = summary,
        status = AgentAppEventStatus.entries.firstOrNull { it.wireValue == status },
        createdAt = createdAt
    )
}

/** Web `JSON.stringify(payload, null, 2)`. */
@OptIn(ExperimentalSerializationApi::class)
private val agentAppEventPayloadJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

@Serializable
private data class AgentIntegrationsErrorDto(
    val error: String? = null
)

@Serializable
private data class IntegrationOverviewItemDto(
    val id: String? = null,
    val type: String? = null,
    val serverId: String? = null,
    val agentId: String? = null,
    val agentName: String? = null,
    val agentDisplayName: String? = null,
    val clientId: String? = null,
    val clientKey: String? = null,
    val clientName: String? = null,
    val clientDescription: String? = null,
    val clientHomepageUrl: String? = null,
    val clientReturnUrl: String? = null,
    val clientAgentManifestUrl: String? = null,
    val scopes: List<String> = emptyList(),
    val remember: Boolean = false,
    val createdAt: String? = null,
    val resolvedAt: String? = null,
    val resolvedByUserId: String? = null,
    val revokedAt: String? = null,
    val grantSource: String? = null,
)

private fun IntegrationOverviewItemDto.toAgentAppLoginItem(): AgentAppLoginItem? {
    val itemId = id.nonBlankOrNull()
        ?: clientId.nonBlankOrNull()
        ?: clientKey.nonBlankOrNull()
        ?: return null
    val client = clientId.nonBlankOrNull()
        ?: clientKey.nonBlankOrNull()
        ?: return null
    val name = clientName.nonBlankOrNull()
        ?: clientKey.nonBlankOrNull()
        ?: client
    return AgentAppLoginItem(
        id = itemId,
        type = type.toAgentAppLoginType(),
        clientId = client,
        clientKey = clientKey.nonBlankOrNull(),
        clientName = name,
        clientDescription = clientDescription.nonBlankOrNull(),
        clientHomepageUrl = clientHomepageUrl.nonBlankOrNull(),
        clientReturnUrl = clientReturnUrl.nonBlankOrNull(),
        clientAgentManifestUrl = clientAgentManifestUrl.nonBlankOrNull(),
        scopes = scopes.filter { it.isNotBlank() },
        remember = remember,
        createdAt = createdAt.nonBlankOrNull(),
        resolvedAt = resolvedAt.nonBlankOrNull(),
        resolvedByUserId = resolvedByUserId.nonBlankOrNull(),
        revokedAt = revokedAt.nonBlankOrNull(),
        grantSource = grantSource.nonBlankOrNull()
    )
}

private fun String?.toAgentAppLoginType(): AgentAppLoginType =
    when (this?.trim()?.lowercase()) {
        "pending" -> AgentAppLoginType.Pending
        else -> AgentAppLoginType.Active
    }

private fun String?.nonBlankOrNull(): String? = this?.takeIf { it.isNotBlank() }

@Serializable
private data class IntegrationClientDto(
    val id: String,
    val name: String,
)

@Serializable
private data class CreateIntegrationClientResponseDto(
    val client: IntegrationClientDto,
    val clientSecret: String? = null,
)

@Serializable
private data class ApproveIntegrationResponseDto(
    val request: IntegrationRequestDto,
    val grantId: String? = null,
)

@Serializable
private data class IntegrationRequestDto(
    val id: String,
    val status: String? = null,
)

@Serializable
private data class ActionCardResponseDto(
    val messageId: String,
    val metadata: WireActionCardMetadata? = null,
)

@Serializable
private data class SeatRequestCheckResponseDto(
    val requesterAllowed: Boolean,
    val seatsShort: Int,
    val pricing: String? = null,
)

@Serializable
private data class OkResponseDto(val ok: Boolean)

@Serializable
private data class PushVapidKeyResponseDto(val publicKey: String)

@Serializable
private data class PushTestResponseDto(
    val ok: Boolean,
    val attempted: Int? = null,
    val delivered: Int? = null,
    val failed: Int? = null,
    val stale: Int? = null,
)

// ── Request bodies ──

/** Body of POST /api/integrations/grants/:grantId/revoke: the web's `EMPTY_BODY`; the route reads no field. */
internal const val REVOKE_INTEGRATION_GRANT_BODY: String = "{}"

/**
 * Body of POST /api/actions/:messageId/execute (botiverse/slock shared `ExecuteActionRequestBody`).
 * [expectedConfirmationVersion] is the card's `confirmationVersion`; left out when unknown.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class ExecuteActionBody(
    val expectedState: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val expectedConfirmationVersion: Int? = null,
    /**
     * `form.v1` (shared `ActionCardFormSubmission`): the form an `open_form` button opened and its
     * values. Left out of the body when null, so a plain execute sends exactly what it always did.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val form: ExecuteActionFormBody? = null,
)

/** Shared `ActionCardFormSubmission`: `{ id, values }`, a value per field (string, boolean or ids). */
@Serializable
internal data class ExecuteActionFormBody(
    val id: String,
    val values: Map<String, @Serializable(with = ActionCardFormValueSerializer::class) ActionCardFormValue>,
)

/** Shared `ActionCardFormValue = string | boolean | string[] | ActionCardChoiceValue` (`{ selected, other? }`, a Choice's). */
internal object ActionCardFormValueSerializer : KSerializer<ActionCardFormValue> {
    override val descriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ActionCardFormValue) {
        val element =
            when (value) {
                is ActionCardFormValue.Text -> JsonPrimitive(value.value)
                is ActionCardFormValue.Toggle -> JsonPrimitive(value.value)
                is ActionCardFormValue.Ids -> JsonArray(value.ids.map(::JsonPrimitive))
                is ActionCardFormValue.Choice ->
                    buildJsonObject {
                        put("selected", JsonArray(value.selected.map(::JsonPrimitive)))
                        value.other?.let { put("other", it) }
                    }
            }
        encoder.encodeSerializableValue(JsonElement.serializer(), element)
    }

    override fun deserialize(decoder: Decoder): ActionCardFormValue =
        when (val element = decoder.decodeSerializableValue(JsonElement.serializer())) {
            is JsonArray -> ActionCardFormValue.Ids(element.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw SerializationException("id") })
            is JsonPrimitive ->
                when {
                    element.isString -> ActionCardFormValue.Text(element.content)
                    element.booleanOrNull != null -> ActionCardFormValue.Toggle(element.boolean)
                    else -> throw SerializationException("form value")
                }
            is JsonObject -> {
                val selected = element["selected"] as? JsonArray ?: throw SerializationException("choice selected")
                ActionCardFormValue.Choice(
                    selected = selected.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw SerializationException("choice value") },
                    other = element["other"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw SerializationException("choice other") },
                )
            }
            else -> throw SerializationException("form value")
        }
}

/** Body of POST /api/actions/:messageId/mark-executed (shared `MarkActionExecutedRequestBody`). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class MarkActionExecutedBody(
    val result: ActionCardResultBody,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val expectedConfirmationVersion: Int? = null,
)

@Serializable
internal data class ActionCardResultBody(
    val kind: String,
    val id: String? = null,
    val name: String? = null,
    val channelId: String? = null,
    val channelName: String? = null,
    val addedHumanIds: List<String>? = null,
    val addedAgentIds: List<String>? = null,
)

internal fun buildExecuteActionBody(
    expectedState: String? = null,
    expectedConfirmationVersion: Int? = null,
    form: ExecuteActionFormBody? = null,
): String =
    integrationsActionsPushJson.encodeToString(
        ExecuteActionBody(expectedState = expectedState, expectedConfirmationVersion = expectedConfirmationVersion, form = form)
    )

internal fun buildMarkAgentActionExecutedBody(agentId: String, agentName: String, expectedConfirmationVersion: Int? = null): String =
    integrationsActionsPushJson.encodeToString(
        MarkActionExecutedBody(
            result = ActionCardResultBody(kind = "agent", id = agentId, name = agentName),
            expectedConfirmationVersion = expectedConfirmationVersion,
        )
    )

internal fun buildMarkChannelActionExecutedBody(channelId: String, channelName: String, expectedConfirmationVersion: Int? = null): String =
    integrationsActionsPushJson.encodeToString(
        MarkActionExecutedBody(
            result = ActionCardResultBody(kind = "channel", id = channelId, name = channelName),
            expectedConfirmationVersion = expectedConfirmationVersion,
        )
    )

internal fun buildMarkChannelMembersActionExecutedBody(
    channelId: String,
    channelName: String,
    addedHumanIds: List<String>,
    addedAgentIds: List<String>,
    expectedConfirmationVersion: Int? = null,
): String =
    integrationsActionsPushJson.encodeToString(
        MarkActionExecutedBody(
            result =
                ActionCardResultBody(
                    kind = "channel-members",
                    channelId = channelId,
                    channelName = channelName,
                    addedHumanIds = addedHumanIds,
                    addedAgentIds = addedAgentIds
                ),
            expectedConfirmationVersion = expectedConfirmationVersion,
        )
    )
