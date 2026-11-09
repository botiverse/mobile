package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.MessageId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class AgentAppEventsApiTest {
    @Test
    fun agentAppEventsReadsTheSharedListShape() = runBlocking {
        val transport =
            EventsTransport(
                list =
                    HomeNetworkResponse(
                        code = 200,
                        bodyText =
                            """{"events":[{"id":"ev-1","app":{"clientId":"c-1","clientKey":"linear","name":"Linear","logoUrl":null},""" +
                                """"kind":"notification","summary":"Issue assigned","status":"delivered","externalEventId":null,""" +
                                """"createdAt":"2026-11-04T07:00:00.000Z","deliveredAt":"2026-11-04T07:00:01.000Z",""" +
                                """"expiresAt":"2026-11-05T07:00:00.000Z","payloadBytes":12},""" +
                                """{"id":"ev-2","app":{"clientId":"c-2","clientKey":"x","name":"Other","logoUrl":null},""" +
                                """"kind":"event","summary":"Later","status":"brand-new","externalEventId":null,""" +
                                """"createdAt":"2026-11-04T06:00:00.000Z","deliveredAt":null,"expiresAt":"2026-11-05T06:00:00.000Z","payloadBytes":2}],""" +
                                """"nextCursor":"cursor-2"}"""
                    )
            )
        val client = RaftIntegrationsActionsPushApiClient(transport)

        val page = assertIs<DataResult.Content<AgentAppEventPage>>(client.agentAppEvents(MemberId("agent-1"), "c-1", "cursor-1")).data

        assertEquals(Triple("agent-1", "c-1", "cursor-1"), transport.listCall)
        assertEquals(listOf("ev-1", "ev-2"), page.events.map { it.id })
        assertEquals("Linear", page.events[0].appName)
        assertEquals("c-1", page.events[0].appClientId)
        assertEquals("notification", page.events[0].kind)
        assertEquals("Issue assigned", page.events[0].summary)
        assertEquals(AgentAppEventStatus.Delivered, page.events[0].status)
        assertEquals("2026-11-04T07:00:00.000Z", page.events[0].createdAt)
        assertNull(page.events[1].status, "a status this build does not know shows no badge")
        assertEquals("cursor-2", page.nextCursor)
    }

    @Test
    fun agentAppEventsLastPageAndEmptyList() = runBlocking {
        val client =
            RaftIntegrationsActionsPushApiClient(
                EventsTransport(list = HomeNetworkResponse(code = 200, bodyText = """{"events":[],"nextCursor":null}"""))
            )

        val page = assertIs<DataResult.Content<AgentAppEventPage>>(client.agentAppEvents(MemberId("agent-1"), null, null)).data

        assertEquals(emptyList(), page.events)
        assertNull(page.nextCursor)
    }

    @Test
    fun agentAppEventsForbiddenIsNoPermission() = runBlocking {
        val client =
            RaftIntegrationsActionsPushApiClient(
                EventsTransport(list = HomeNetworkResponse(code = 403, bodyText = """{"error":"Not allowed"}"""))
            )

        val blocked = assertIs<DataResult.NoPermission>(client.agentAppEvents(MemberId("agent-1"), null, null))

        assertEquals("Not allowed", blocked.blocker.serverMessage)
    }

    @Test
    fun agentAppEventPayloadIsPrettyPrintedJson() = runBlocking {
        val client =
            RaftIntegrationsActionsPushApiClient(
                EventsTransport(
                    detail =
                        HomeNetworkResponse(
                            code = 200,
                            bodyText = """{"id":"ev-1","summary":"Issue assigned","payload":{"issue":"RAFT-1","count":2}}"""
                        )
                )
            )

        val detail = assertIs<DataResult.Content<AgentAppEventDetail>>(client.agentAppEvent(MemberId("agent-1"), "ev-1")).data

        assertEquals("ev-1", detail.eventId)
        assertEquals("{\n  \"issue\": \"RAFT-1\",\n  \"count\": 2\n}", detail.payloadText)
    }

    private class EventsTransport(
        private val list: HomeNetworkResponse = HomeNetworkResponse(code = 500, bodyText = null),
        private val detail: HomeNetworkResponse = HomeNetworkResponse(code = 500, bodyText = null),
    ) : RaftIntegrationsActionsPushApi {
        var listCall: Triple<String, String?, String?>? = null

        override suspend fun agentAppEvents(agentId: MemberId, clientId: String?, before: String?): HomeNetworkResponse {
            listCall = Triple(agentId.value, clientId, before)
            return list
        }

        override suspend fun agentAppEvent(agentId: MemberId, eventId: String): HomeNetworkResponse = detail

        override suspend fun integrationsOverview() = unused()
        override suspend fun integrationClients() = unused()
        override suspend fun createIntegrationClient(body: String) = unused()
        override suspend fun approveIntegrationRequest(requestId: String, body: String) = unused()
        override suspend fun denyIntegrationRequest(requestId: String) = unused()
        override suspend fun revokeIntegrationGrant(grantId: String) = unused()
        override suspend fun agentIntegrations(agentId: MemberId) = unused()
        override suspend fun executeAction(messageId: MessageId, body: String) = unused()
        override suspend fun emitActionEvent(messageId: MessageId, body: String) = unused()
        override suspend fun markActionExecuted(messageId: MessageId, body: String) = unused()
        override suspend fun reconfirmAction(messageId: MessageId) = unused()
        override suspend fun pushVapidKey() = unused()
        override suspend fun recordPushPromptEvent(body: String) = unused()
        override suspend fun subscribePush(body: String) = unused()
        override suspend fun unsubscribePush(body: String) = unused()
        override suspend fun testPush() = unused()

        private fun unused(): HomeNetworkResponse = error("unused")
    }
}
