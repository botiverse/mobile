package build.raft.app

import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.AgentMigrateActionCardType
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftIntegrationsActionsPushApi
import build.raft.app.data.RaftIntegrationsActionsPushApiClient
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.WireActionCardAction
import build.raft.app.data.WireActionCardMetadata
import build.raft.app.data.WireActionCardResult
import build.raft.app.data.actionCardFromMessageMetadata
import build.raft.app.data.buildMarkAgentMigrationActionExecutedBody
import build.raft.app.data.isAgentMigrateAction
import build.raft.app.data.isGenericInlineCommitAction
import build.raft.app.data.isSupportedCommitAction
import build.raft.app.data.toMessageMetadata
import build.raft.app.data.toThreadActionCard
import build.raft.app.data.toWireJsonObject
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.thread.AgentMigrateCardAccess
import build.raft.app.thread.ThreadActionCardCommands
import build.raft.app.thread.agentMigrateCardPlacesWithoutSource
import build.raft.app.thread.evaluateAgentMigrateCardAccess
import build.raft.app.thread.resolveThreadMigrateAgentActionTarget
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadActionCardAgentMigrateTest {
    private val agentId = "3e4f5a6b-7c8d-4e9f-8a1b-2c3d4e5f6071"
    private val targetMachineId = "5a6b7c8d-9e0f-4a1b-8c3d-4e5f60718293"

    @Test
    fun agentMigrateWireAndMetadataRoundTrip() {
        val wire =
            WireActionCardMetadata(
                kind = "action-card",
                state = "executed",
                confirmationVersion = 3,
                action =
                    WireActionCardAction(
                        type = AgentMigrateActionCardType,
                        agentId = agentId,
                        suggestedComputer = targetMachineId,
                        draftHint = "Source computer is low on RAM",
                    ).toWireJsonObject(),
                result =
                    WireActionCardResult(
                        kind = "agent-migration",
                        agentId = agentId,
                        targetMachineId = targetMachineId,
                    ),
            )
        val parsed = assertNotNull(wire.toThreadActionCard())
        assertTrue(parsed.isAgentMigrateAction())
        assertTrue(parsed.isSupportedCommitAction())
        assertFalse(parsed.isGenericInlineCommitAction())
        assertEquals(agentId, parsed.agentId)
        assertEquals(targetMachineId, parsed.suggestedComputer)
        assertEquals("Source computer is low on RAM", parsed.draftHint)
        assertEquals("agent-migration", parsed.resultKind)
        assertEquals(agentId, parsed.resultId)
        assertEquals(targetMachineId, parsed.resultTargetMachineId)

        val roundTripped = assertNotNull(actionCardFromMessageMetadata(parsed.toMessageMetadata()))
        assertEquals(parsed, roundTripped)
    }

    @Test
    fun buildMarkAgentMigrationActionExecutedBodyMatchesServerContract() {
        val withoutVersion =
            Json.parseToJsonElement(
                buildMarkAgentMigrationActionExecutedBody(agentId = agentId, targetMachineId = targetMachineId)
            ).jsonObject
        assertEquals(setOf("result"), withoutVersion.keys)
        val result = withoutVersion.getValue("result").jsonObject
        assertEquals(setOf("kind", "agentId", "targetMachineId"), result.keys)
        assertEquals("agent-migration", result.getValue("kind").jsonPrimitive.content)
        assertEquals(agentId, result.getValue("agentId").jsonPrimitive.content)
        assertEquals(targetMachineId, result.getValue("targetMachineId").jsonPrimitive.content)

        val withVersion =
            Json.parseToJsonElement(
                buildMarkAgentMigrationActionExecutedBody(
                    agentId = agentId,
                    targetMachineId = targetMachineId,
                    expectedConfirmationVersion = 4,
                )
            ).jsonObject
        assertEquals(setOf("result", "expectedConfirmationVersion"), withVersion.keys)
        assertEquals(4, withVersion.getValue("expectedConfirmationVersion").jsonPrimitive.int)
    }

    @Test
    fun evaluateAgentMigrateCardAccessMatchesWebPermissionMatrix() {
        val card = ThreadActionCard(type = AgentMigrateActionCardType, agentId = agentId)
        val sharedAgent =
            MemberProfile(
                id = MemberId(agentId),
                displayName = "Atlas",
                handle = "atlas",
                kind = MemberKind.Agent,
                agentCreatorUserId = "creator-1",
            )
        val personalAssistant =
            sharedAgent.copy(
                displayName = "Aria",
                assistantOwnerUserId = "owner-user-1",
            )
        val deletedAgent = sharedAgent.copy(agentDeletedAt = "2026-11-20T00:00:00.000Z")

        // Before roster loads: don't falsely block.
        assertNull(evaluateAgentMigrateCardAccess(card, "member-2", "member", emptyList(), rosterLoaded = false))
        // After roster loads with agent absent: blocked.
        assertEquals(
            AgentMigrateCardAccess(canMigrate = false, agentName = null),
            evaluateAgentMigrateCardAccess(card, "owner-1", "owner", emptyList(), rosterLoaded = true),
        )
        // Shared agent: owner/admin and creator allowed; plain other member blocked.
        assertEquals(
            AgentMigrateCardAccess(canMigrate = true, agentName = "Atlas"),
            evaluateAgentMigrateCardAccess(card, "admin-1", "admin", listOf(sharedAgent), rosterLoaded = true),
        )
        assertEquals(
            AgentMigrateCardAccess(canMigrate = true, agentName = "Atlas"),
            evaluateAgentMigrateCardAccess(card, "creator-1", "member", listOf(sharedAgent), rosterLoaded = true),
        )
        assertEquals(
            AgentMigrateCardAccess(canMigrate = false, agentName = "Atlas"),
            evaluateAgentMigrateCardAccess(card, "other-member", "member", listOf(sharedAgent), rosterLoaded = true),
        )
        // Personal assistant: only its owner can migrate, even if viewer is a server owner.
        assertEquals(
            AgentMigrateCardAccess(canMigrate = true, agentName = "Aria"),
            evaluateAgentMigrateCardAccess(card, "owner-user-1", "member", listOf(personalAssistant), rosterLoaded = true),
        )
        assertEquals(
            AgentMigrateCardAccess(canMigrate = false, agentName = "Aria"),
            evaluateAgentMigrateCardAccess(card, "server-owner", "owner", listOf(personalAssistant), rosterLoaded = true),
        )
        // Deleted agent cannot be migrated.
        assertEquals(
            AgentMigrateCardAccess(canMigrate = false, agentName = null),
            evaluateAgentMigrateCardAccess(card, "server-owner", "owner", listOf(deletedAgent), rosterLoaded = true),
        )
    }

    @Test
    fun agentMigrateCardPlacesWithoutSourceMatchesWeb() {
        assertTrue(agentMigrateCardPlacesWithoutSource(executionMode = "byoc", machineId = null, blockedReason = null))
        assertTrue(agentMigrateCardPlacesWithoutSource(executionMode = "byoc", machineId = "", blockedReason = null))
        assertTrue(
            agentMigrateCardPlacesWithoutSource(
                executionMode = "byoc",
                machineId = "m-revoked",
                blockedReason = "computer_access_revoked",
            )
        )
        assertFalse(agentMigrateCardPlacesWithoutSource(executionMode = "byoc", machineId = "m-source", blockedReason = null))
        assertFalse(agentMigrateCardPlacesWithoutSource(executionMode = "cloud", machineId = null, blockedReason = null))
        assertFalse(
            agentMigrateCardPlacesWithoutSource(
                executionMode = "cloud",
                machineId = null,
                blockedReason = "computer_access_revoked",
            )
        )
        // Deleted agent returns null target.
        assertNull(
            resolveThreadMigrateAgentActionTarget(
                agentId = agentId,
                memberProfile = null,
                detail = AgentDetailSummary(id = agentId, name = "atlas", deletedAt = "2026-11-24T00:00:00Z"),
            )
        )
    }

    @Test
    fun markAgentMigrationActionCardExecutedSendsExactPayloadAndReturnsCard() = runTest {
        var capturedMessageId: String? = null
        var capturedBody: String? = null
        val transport =
            object : RaftIntegrationsActionsPushApi {
                override suspend fun integrationsOverview() = error("unused")
                override suspend fun integrationClients() = error("unused")
                override suspend fun createIntegrationClient(body: String) = error("unused")
                override suspend fun approveIntegrationRequest(requestId: String, body: String) = error("unused")
                override suspend fun denyIntegrationRequest(requestId: String) = error("unused")
                override suspend fun revokeIntegrationGrant(grantId: String) = error("unused")
                override suspend fun agentIntegrations(agentId: MemberId) = error("unused")
                override suspend fun executeAction(messageId: MessageId, body: String) = error("unused")
                override suspend fun emitActionEvent(messageId: MessageId, body: String) = error("unused")
                override suspend fun reconfirmAction(messageId: MessageId) = error("unused")
                override suspend fun pushVapidKey() = error("unused")
                override suspend fun recordPushPromptEvent(body: String) = error("unused")
                override suspend fun subscribePush(body: String) = error("unused")
                override suspend fun unsubscribePush(body: String) = error("unused")
                override suspend fun testPush() = error("unused")
                override suspend fun markActionExecuted(messageId: MessageId, body: String): HomeNetworkResponse {
                    capturedMessageId = messageId.value
                    capturedBody = body
                    return HomeNetworkResponse(
                        code = 200,
                        bodyText =
                            """{"messageId":"${messageId.value}","metadata":{"kind":"action-card","state":"executed","executedByUserName":"Alice","action":{"type":"agent:migrate","agentId":"$agentId","suggestedComputer":"$targetMachineId"},"result":{"kind":"agent-migration","agentId":"$agentId","targetMachineId":"$targetMachineId"}}}"""
                    )
                }
            }
        val commands =
            ThreadActionCardCommands(
                agentsApiClient = null,
                actionCardsApiClient = RaftIntegrationsActionsPushApiClient(transport),
                createAgentCoordinator = null,
                channelRepository = null,
                memberRepository = null,
                serverMembersStore = null,
                readSession = { null },
            )
        val card =
            ThreadActionCard(
                type = AgentMigrateActionCardType,
                agentId = agentId,
                suggestedComputer = targetMachineId,
                confirmationVersion = 2,
            )
        val result = commands.markAgentMigrationActionCardExecuted("msg-42", card, agentId, targetMachineId)
        assertEquals("msg-42", capturedMessageId)
        val sentJson = Json.parseToJsonElement(assertNotNull(capturedBody)).jsonObject
        assertEquals(2, sentJson.getValue("expectedConfirmationVersion").jsonPrimitive.int)
        assertEquals("agent-migration", sentJson.getValue("result").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals(agentId, sentJson.getValue("result").jsonObject.getValue("agentId").jsonPrimitive.content)
        assertEquals(targetMachineId, sentJson.getValue("result").jsonObject.getValue("targetMachineId").jsonPrimitive.content)

        val updated = (result as DataResult.Content).data
        assertTrue(updated.isExecuted)
        assertEquals("Alice", updated.executedByUserName)
        assertEquals("agent-migration", updated.resultKind)
        assertEquals(targetMachineId, updated.resultTargetMachineId)
    }
}
