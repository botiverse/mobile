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

    @Test
    fun computerItemSurfacesRuntimeAndCreatedFields() {
        // The new upstream read-model fields (statusVersion / apiKeyPrefix /
        // runtimes / lastHeartbeat / createdAt) must round-trip through the
        // page payload so the React-aligned Settings ComputerDetail page can
        // render them — see #74 task description and machineStore Machine
        // interface.
        val payload =
            KuiklySettingsComputerDetailPayload(
                instanceId = "computer-detail",
                computer =
                    KuiklySettingsComputerItem(
                        id = "machine-1",
                        name = "Maria Laptop",
                        daemonVersion = "0.53.0",
                        online = true,
                        hostname = "maria.local",
                        platform = "darwin",
                        lastSeenLabel = "2026-06-14T02:00:00.000Z",
                        runtimes = listOf("claude", "codex"),
                        createdAt = "2026-05-01T00:00:00.000Z",
                        apiKeyPrefix = "sk_machine_abc",
                        statusVersion = 7L
                    )
            )

        val decoded =
            json.decodeFromString(
                KuiklySettingsComputerDetailPayload.serializer(),
                json.encodeToString(payload)
            )

        val c = decoded.computer
        assertEquals(listOf("claude", "codex"), c.runtimes)
        assertEquals("2026-05-01T00:00:00.000Z", c.createdAt)
        assertEquals("sk_machine_abc", c.apiKeyPrefix)
        assertEquals(7L, c.statusVersion)
        assertEquals("2026-06-14T02:00:00.000Z", c.lastSeenLabel)
    }
}
