package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.MessageId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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
 */
internal interface RaftIntegrationsActionsPushApi {
    suspend fun integrationsOverview(): HomeNetworkResponse
    suspend fun integrationClients(): HomeNetworkResponse
    suspend fun createIntegrationClient(body: String): HomeNetworkResponse
    suspend fun approveIntegrationRequest(requestId: String, body: String): HomeNetworkResponse
    suspend fun denyIntegrationRequest(requestId: String): HomeNetworkResponse
    suspend fun revokeIntegrationGrant(grantId: String): HomeNetworkResponse
    suspend fun agentIntegrations(agentId: MemberId): HomeNetworkResponse
    suspend fun executeAction(messageId: MessageId, body: String): HomeNetworkResponse
    suspend fun emitActionEvent(messageId: MessageId, body: String): HomeNetworkResponse
    suspend fun markActionExecuted(messageId: MessageId, body: String): HomeNetworkResponse
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
        networkClient.request(DataRequestPriority.Foreground, "integrations/grants/$grantId/revoke", method = "POST")

    override suspend fun agentIntegrations(agentId: MemberId): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Visible, "integrations/agents/${agentId.value}")

    override suspend fun executeAction(messageId: MessageId, body: String): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Foreground, "actions/${messageId.value}/execute", method = "POST", bodyText = body)

    override suspend fun emitActionEvent(messageId: MessageId, body: String): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Background, "actions/${messageId.value}/event", method = "POST", bodyText = body)

    override suspend fun markActionExecuted(messageId: MessageId, body: String): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Foreground, "actions/${messageId.value}/mark-executed", method = "POST", bodyText = body)

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

    suspend fun revokeIntegrationGrant(grantId: String): DataResult<IntegrationGrantResult> =
        transport.revokeIntegrationGrant(grantId).mapApiBody(
            "integration_grant_revoke_contract_mismatch",
            IntegrationGrantDto.serializer()
        ) { wire ->
            val id = wire.id.takeIf { it.isNotBlank() } ?: return@mapApiBody null
            IntegrationGrantResult(grantId = id, status = wire.status, revokedAt = wire.revokedAt)
        }

    suspend fun agentIntegrations(agentId: MemberId): DataResult<AgentIntegrationsPage> =
        transport.agentIntegrations(agentId).mapAgentIntegrations(agentId)

    suspend fun executeAction(messageId: MessageId, body: String): DataResult<ActionCardResult> =
        transport.executeAction(messageId, body).mapActionCard("action_execute_contract_mismatch")

    suspend fun emitActionEvent(messageId: MessageId, body: String): DataResult<ActionEventResult> =
        transport.emitActionEvent(messageId, body).mapActionEvent("action_event_contract_mismatch")

    suspend fun markActionExecuted(messageId: MessageId, body: String): DataResult<ActionCardResult> =
        transport.markActionExecuted(messageId, body).mapActionCard("action_mark_executed_contract_mismatch")

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
internal data class IntegrationGrantResult(val grantId: String, val status: String?, val revokedAt: String?)
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
        return DataResult.Error(ScreenError(code = contractCode, retryable = code >= 500))
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

private fun HomeNetworkResponse.mapActionCard(contractCode: String): DataResult<ActionCardResult> =
    mapApiBody(contractCode, ActionCardResponseDto.serializer()) { wire ->
        val id = wire.messageId.takeIf { it.isNotBlank() } ?: return@mapApiBody null
        val card = wire.metadata.toThreadActionCard()
        ActionCardResult(messageId = id, metadataPresent = wire.metadata != null, actionCard = card)
    }

private fun HomeNetworkResponse.mapPushCommand(contractCode: String): DataResult<PushCommandResult> =
    mapApiBody(contractCode, OkResponseDto.serializer()) { wire ->
        if (!wire.ok) null else PushCommandResult(ok = true)
    }

private fun HomeNetworkResponse.mapAgentIntegrations(agentId: MemberId): DataResult<AgentIntegrationsPage> {
    val contractCode = "agent_integrations_contract_mismatch"
    if (!isSuccessful) {
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

/** Exact non-2xx envelope for `GET /api/integrations/agents/:agentId`. */
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
        revokedAt = revokedAt.nonBlankOrNull()
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
private data class IntegrationGrantDto(
    val id: String,
    val status: String? = null,
    val revokedAt: String? = null,
)

@Serializable
private data class ActionCardResponseDto(
    val messageId: String,
    val metadata: WireActionCardMetadata? = null,
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

@Serializable
internal data class CreateIntegrationClientBody(
    val name: String,
    val description: String? = null,
    val homepageUrl: String? = null,
    val clientId: String? = null
)

@Serializable
internal data class ApproveIntegrationRequestBody(val remember: Boolean)

@Serializable
internal data class ExecuteActionBody(val expectedState: String? = null)

@Serializable
internal data class MarkActionExecutedBody(val result: ActionCardResultBody)

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

@Serializable
internal data class ActionEventBody(
    val eventType: String,
    val metadata: JsonObject? = null,
    val idempotencyKey: String? = null
)

@Serializable
internal data class PushPromptEventBody(
    val event: String,
    val trigger: String,
    val result: String? = null,
    val permissionBefore: String? = null,
    val permissionAfter: String? = null,
    val detail: String? = null
)

@Serializable
internal data class PushSubscriptionBody(val endpoint: String, val keys: PushSubscriptionKeys)

@Serializable
internal data class PushSubscriptionKeys(val p256dh: String, val auth: String)

@Serializable
internal data class PushUnsubscribeBody(val endpoint: String)

internal fun buildCreateIntegrationClientBody(
    name: String,
    description: String? = null,
    homepageUrl: String? = null,
    clientId: String? = null,
): String = integrationsActionsPushJson.encodeToString(
    CreateIntegrationClientBody(name = name, description = description, homepageUrl = homepageUrl, clientId = clientId)
)

internal fun buildApproveIntegrationRequestBody(remember: Boolean): String =
    integrationsActionsPushJson.encodeToString(ApproveIntegrationRequestBody(remember = remember))

internal fun buildExecuteActionBody(expectedState: String? = null): String =
    integrationsActionsPushJson.encodeToString(ExecuteActionBody(expectedState = expectedState))

internal fun buildMarkAgentActionExecutedBody(agentId: String, agentName: String): String =
    integrationsActionsPushJson.encodeToString(
        MarkActionExecutedBody(
            result = ActionCardResultBody(kind = "agent", id = agentId, name = agentName)
        )
    )

internal fun buildMarkChannelActionExecutedBody(channelId: String, channelName: String): String =
    integrationsActionsPushJson.encodeToString(
        MarkActionExecutedBody(
            result = ActionCardResultBody(kind = "channel", id = channelId, name = channelName)
        )
    )

internal fun buildMarkChannelMembersActionExecutedBody(
    channelId: String,
    channelName: String,
    addedHumanIds: List<String>,
    addedAgentIds: List<String>
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
                )
        )
    )

internal fun buildActionEventBody(
    eventType: String,
    metadata: JsonObject? = null,
    idempotencyKey: String? = null,
): String = integrationsActionsPushJson.encodeToString(
    ActionEventBody(eventType = eventType, metadata = metadata, idempotencyKey = idempotencyKey)
)

internal fun buildPushPromptEventBody(
    event: String,
    trigger: String,
    result: String? = null,
    permissionBefore: String? = null,
    permissionAfter: String? = null,
    detail: String? = null,
): String = integrationsActionsPushJson.encodeToString(
    PushPromptEventBody(
        event = event,
        trigger = trigger,
        result = result,
        permissionBefore = permissionBefore,
        permissionAfter = permissionAfter,
        detail = detail,
    )
)

internal fun buildPushSubscribeBody(endpoint: String, p256dh: String, auth: String): String =
    integrationsActionsPushJson.encodeToString(
        PushSubscriptionBody(endpoint = endpoint, keys = PushSubscriptionKeys(p256dh = p256dh, auth = auth))
    )

internal fun buildPushUnsubscribeBody(endpoint: String): String =
    integrationsActionsPushJson.encodeToString(PushUnsubscribeBody(endpoint = endpoint))
