package ai.slock.compose.data

import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MessageId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val integrationsActionsPushJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Typed Integrations/Actions/Push boundary.
 *
 * Overview/clients/grants stay on the upstream roots parsed below; action
 * event telemetry treats empty 204 responses as success, while action execute
 * and mark-executed require a typed action-card body. Push VAPID requires
 * `publicKey`; push command routes require canonical success bodies.
 */
internal interface SlockIntegrationsActionsPushApi {
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

internal class SlockIntegrationsActionsPushApiClient(
    private val transport: SlockIntegrationsActionsPushApi
) {
    suspend fun integrationsOverview(): DataResult<IntegrationsOverviewPage> =
        transport.integrationsOverview().mapApiArray("integrations_overview_contract_mismatch") { items ->
            IntegrationsOverviewPage(itemCount = items.size, firstClientName = items.firstOrNullObject()?.string("clientName"))
        }

    suspend fun integrationClients(): DataResult<IntegrationClientsPage> =
        transport.integrationClients().mapApiArray("integration_clients_contract_mismatch") { clients ->
            IntegrationClientsPage(clientCount = clients.size, firstName = clients.firstOrNullObject()?.string("name"))
        }

    suspend fun createIntegrationClient(body: String): DataResult<IntegrationClientCreateResult> =
        transport.createIntegrationClient(body).mapApiBody("integration_client_create_contract_mismatch") { root ->
            val client = root["client"] as? JsonObject ?: return@mapApiBody null
            IntegrationClientCreateResult(
                clientId = client.string("id")?.takeIf { it.isNotBlank() } ?: client.string("clientId")?.takeIf { it.isNotBlank() }
                    ?: return@mapApiBody null,
                clientSecretPresent = !root.string("clientSecret").isNullOrBlank()
            )
        }

    suspend fun approveIntegrationRequest(requestId: String, body: String): DataResult<IntegrationApproveResult> =
        transport.approveIntegrationRequest(requestId, body).mapApiBody("integration_request_approve_contract_mismatch") { root ->
            val request = root["request"] as? JsonObject ?: return@mapApiBody null
            IntegrationApproveResult(
                requestId = request.string("id")?.takeIf { it.isNotBlank() } ?: return@mapApiBody null,
                grantId = root.string("grantId")
            )
        }

    suspend fun denyIntegrationRequest(requestId: String): DataResult<IntegrationRequestResult> =
        transport.denyIntegrationRequest(requestId).toIntegrationRequest("integration_request_deny_contract_mismatch")

    suspend fun revokeIntegrationGrant(grantId: String): DataResult<IntegrationGrantResult> =
        transport.revokeIntegrationGrant(grantId).mapApiBody("integration_grant_revoke_contract_mismatch") { root ->
            IntegrationGrantResult(
                grantId = root.string("id")?.takeIf { it.isNotBlank() } ?: return@mapApiBody null,
                status = root.string("status") ?: root.string("state"),
                revokedAt = root.string("revokedAt")
            )
        }

    suspend fun agentIntegrations(agentId: MemberId): DataResult<AgentIntegrationsPage> =
        transport.agentIntegrations(agentId).mapApiArray("agent_integrations_contract_mismatch") { integrations ->
            AgentIntegrationsPage(itemCount = integrations.size, firstClientName = integrations.firstOrNullObject()?.string("clientName"))
        }

    suspend fun executeAction(messageId: MessageId, body: String): DataResult<ActionCardResult> =
        transport.executeAction(messageId, body).toActionCardResult("action_execute_contract_mismatch")

    suspend fun emitActionEvent(messageId: MessageId, body: String): DataResult<ActionEventResult> =
        transport.emitActionEvent(messageId, body).mapApiUnit("action_event_contract_mismatch")

    suspend fun markActionExecuted(messageId: MessageId, body: String): DataResult<ActionCardResult> =
        transport.markActionExecuted(messageId, body).toActionCardResult("action_mark_executed_contract_mismatch")

    suspend fun pushVapidKey(): DataResult<PushVapidKey> =
        transport.pushVapidKey().mapApiBody("push_vapid_key_contract_mismatch") { root ->
            PushVapidKey(publicKey = root.string("publicKey")?.takeIf { it.isNotBlank() } ?: return@mapApiBody null)
        }

    suspend fun recordPushPromptEvent(body: String): DataResult<PushCommandResult> =
        transport.recordPushPromptEvent(body).toPushCommand("push_prompt_event_contract_mismatch")

    suspend fun subscribePush(body: String): DataResult<PushCommandResult> =
        transport.subscribePush(body).toPushCommand("push_subscribe_contract_mismatch")

    suspend fun unsubscribePush(body: String): DataResult<PushCommandResult> =
        transport.unsubscribePush(body).toPushCommand("push_unsubscribe_contract_mismatch")

    suspend fun testPush(): DataResult<PushTestResult> =
        transport.testPush().mapApiBody("push_test_contract_mismatch") { root ->
            PushTestResult(
                ok = root.boolean("ok")?.takeIf { it } ?: return@mapApiBody null,
                attempted = root.int("attempted") ?: return@mapApiBody null,
                delivered = root.int("delivered") ?: return@mapApiBody null,
                failed = root.int("failed") ?: return@mapApiBody null,
                stale = root.int("stale")
            )
        }

    private fun HomeNetworkResponse.toIntegrationRequest(contractCode: String): DataResult<IntegrationRequestResult> =
        mapApiBody(contractCode) { root ->
            IntegrationRequestResult(
                requestId = root.string("id")?.takeIf { it.isNotBlank() } ?: return@mapApiBody null,
                status = root.string("status")?.takeIf { it.isNotBlank() } ?: return@mapApiBody null
            )
        }

    private fun HomeNetworkResponse.toActionCardResult(contractCode: String): DataResult<ActionCardResult> =
        mapApiBody(contractCode) { root ->
            ActionCardResult(
                messageId = root.string("messageId")?.takeIf { it.isNotBlank() } ?: return@mapApiBody null,
                metadataPresent = root["metadata"] is JsonObject
            )
        }

    private fun HomeNetworkResponse.toPushCommand(contractCode: String): DataResult<PushCommandResult> =
        mapApiBody(contractCode) { root ->
            PushCommandResult(ok = root.boolean("ok")?.takeIf { it } ?: return@mapApiBody null)
        }
}

internal data class IntegrationsOverviewPage(val itemCount: Int, val firstClientName: String?)
internal data class IntegrationClientsPage(val clientCount: Int, val firstName: String?)
internal data class IntegrationClientCreateResult(val clientId: String, val clientSecretPresent: Boolean)
internal data class IntegrationApproveResult(val requestId: String, val grantId: String?)
internal data class IntegrationRequestResult(val requestId: String, val status: String)
internal data class IntegrationGrantResult(val grantId: String, val status: String?, val revokedAt: String?)
internal data class AgentIntegrationsPage(val itemCount: Int, val firstClientName: String?)
internal data class ActionCardResult(val messageId: String, val metadataPresent: Boolean)
internal data class ActionEventResult(val ok: Boolean)
internal data class PushVapidKey(val publicKey: String)
internal data class PushCommandResult(val ok: Boolean)
internal data class PushTestResult(val ok: Boolean, val attempted: Int, val delivered: Int, val failed: Int, val stale: Int?)

private inline fun <T> HomeNetworkResponse.mapApiBody(
    contractCode: String,
    mapper: (JsonObject) -> T?
): DataResult<T> {
    if (!isSuccessful) {
        return DataResult.Error(ScreenError(code = contractCode, retryable = code >= 500))
    }
    val body = bodyText?.takeIf { it.isNotBlank() }
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    val root = runCatching { integrationsActionsPushJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    val value = mapper(root)
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    return DataResult.Content(value)
}

private inline fun <T> HomeNetworkResponse.mapApiArray(
    contractCode: String,
    mapper: (JsonArray) -> T?
): DataResult<T> {
    if (!isSuccessful) {
        return DataResult.Error(ScreenError(code = contractCode, retryable = code >= 500))
    }
    val body = bodyText?.takeIf { it.isNotBlank() }
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    val root = runCatching { integrationsActionsPushJson.parseToJsonElement(body) as? JsonArray }.getOrNull()
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    val value = mapper(root)
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    return DataResult.Content(value)
}

private fun HomeNetworkResponse.mapApiUnit(contractCode: String): DataResult<ActionEventResult> {
    if (!isSuccessful) {
        return DataResult.Error(ScreenError(code = contractCode, retryable = code >= 500))
    }
    if (bodyText.isNullOrBlank()) {
        return DataResult.Content(ActionEventResult(ok = true))
    }
    val body = bodyText
    val root = runCatching { integrationsActionsPushJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    return if (root.isEmpty()) {
        DataResult.Content(ActionEventResult(ok = true))
    } else {
        DataResult.Error(ScreenError(code = contractCode, retryable = false))
    }
}

private fun JsonArray.firstOrNullObject(): JsonObject? = firstOrNull() as? JsonObject

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }

private fun JsonObject.boolean(key: String): Boolean? =
    when (val primitive = this[key] as? JsonPrimitive) {
        null -> null
        else -> primitive.contentOrNull?.trim()?.lowercase()?.let { value ->
            when (value) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
    }

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()

@kotlinx.serialization.Serializable
internal data class CreateIntegrationClientBody(
    val name: String,
    val description: String? = null,
    val homepageUrl: String? = null,
    val clientId: String? = null
)

@kotlinx.serialization.Serializable
internal data class ApproveIntegrationRequestBody(val remember: Boolean)

@kotlinx.serialization.Serializable
internal data class ExecuteActionBody(val expectedState: String? = null)

@kotlinx.serialization.Serializable
internal data class ActionEventBody(
    val eventType: String,
    val metadata: JsonObject? = null,
    val idempotencyKey: String? = null
)

@kotlinx.serialization.Serializable
internal data class PushPromptEventBody(
    val event: String,
    val trigger: String,
    val result: String? = null,
    val permissionBefore: String? = null,
    val permissionAfter: String? = null,
    val detail: String? = null
)

@kotlinx.serialization.Serializable
internal data class PushSubscriptionBody(val endpoint: String, val keys: PushSubscriptionKeys)

@kotlinx.serialization.Serializable
internal data class PushSubscriptionKeys(val p256dh: String, val auth: String)

@kotlinx.serialization.Serializable
internal data class PushUnsubscribeBody(val endpoint: String)

internal fun buildCreateIntegrationClientBody(name: String, description: String? = null, homepageUrl: String? = null, clientId: String? = null): String =
    integrationsActionsPushJson.encodeToString(
        CreateIntegrationClientBody(name = name, description = description, homepageUrl = homepageUrl, clientId = clientId)
    )

internal fun buildApproveIntegrationRequestBody(remember: Boolean): String =
    integrationsActionsPushJson.encodeToString(ApproveIntegrationRequestBody(remember = remember))

internal fun buildExecuteActionBody(expectedState: String? = null): String =
    integrationsActionsPushJson.encodeToString(ExecuteActionBody(expectedState = expectedState))

internal fun buildActionEventBody(eventType: String, metadata: JsonObject? = null, idempotencyKey: String? = null): String =
    integrationsActionsPushJson.encodeToString(ActionEventBody(eventType = eventType, metadata = metadata, idempotencyKey = idempotencyKey))

internal fun buildPushPromptEventBody(
    event: String,
    trigger: String,
    result: String? = null,
    permissionBefore: String? = null,
    permissionAfter: String? = null,
    detail: String? = null
): String =
    integrationsActionsPushJson.encodeToString(
        PushPromptEventBody(
            event = event,
            trigger = trigger,
            result = result,
            permissionBefore = permissionBefore,
            permissionAfter = permissionAfter,
            detail = detail
        )
    )

internal fun buildPushSubscribeBody(endpoint: String, p256dh: String, auth: String): String =
    integrationsActionsPushJson.encodeToString(PushSubscriptionBody(endpoint = endpoint, keys = PushSubscriptionKeys(p256dh = p256dh, auth = auth)))

internal fun buildPushUnsubscribeBody(endpoint: String): String =
    integrationsActionsPushJson.encodeToString(PushUnsubscribeBody(endpoint = endpoint))
