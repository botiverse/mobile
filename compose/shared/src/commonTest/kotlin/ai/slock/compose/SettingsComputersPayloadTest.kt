package ai.slock.compose

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsComputersPayloadTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun computerDetailAgentsDefaultToReadOnlyForLegacyPayloads() {
        val payload =
            json.decodeFromString(
                KuiklySettingsComputerDetailPayload.serializer(),
                """
                {
                  "instanceId": "computer-detail",
                  "agents": [
                    {"id": "agent-1", "name": "Settings Agent"}
                  ]
                }
                """.trimIndent()
            )

        assertEquals(false, payload.canOpenAgents)
        assertEquals("agent-1", payload.agents.single().id)
    }

    @Test
    fun computerDetailCanOpenAgentsFlagRoundTrips() {
        val payload =
            KuiklySettingsComputerDetailPayload(
                instanceId = "computer-detail",
                agents = listOf(KuiklySettingsAgentItem(id = "agent-1", name = "Settings Agent")),
                canOpenAgents = true
            )

        val decoded =
            json.decodeFromString(
                KuiklySettingsComputerDetailPayload.serializer(),
                json.encodeToString(payload)
            )

        assertEquals(true, decoded.canOpenAgents)
    }
}
