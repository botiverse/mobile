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

    @Test
    fun computersListPayloadRoundTripsDaemonVersionForOnlineAndOfflineMachines() {
        // #77: list card only renders name + status + daemon version line,
        // matching React MobileComputersPanel.tsx + Sidebar.tsx:1248-1290. The
        // payload must carry daemonVersion on the per-item level so the page
        // can fall back to "daemon offline" when it is blank, without
        // pulling in fields the React list doesn't show (createdAt /
        // runtimes / lastHeartbeat / hostname / os live on the detail
        // payload, see #74 commit cf01a0a4).
        val payload =
            KuiklySettingsComputersPayload(
                instanceId = "computers-list",
                latestDaemonVersion = "0.53.0",
                computers =
                    listOf(
                        KuiklySettingsComputerItem(
                            id = "machine-1",
                            name = "Maria Laptop",
                            daemonVersion = "0.53.0",
                            online = true
                        ),
                        KuiklySettingsComputerItem(
                            id = "machine-2",
                            name = "Build host",
                            daemonVersion = "",
                            online = false
                        )
                    )
            )

        val decoded =
            json.decodeFromString(
                KuiklySettingsComputersPayload.serializer(),
                json.encodeToString(payload)
            )

        assertEquals(2, decoded.computers.size)
        val (online, offline) = decoded.computers
        assertEquals("0.53.0", online.daemonVersion)
        assertEquals(true, online.online)
        assertEquals("", offline.daemonVersion)
        assertEquals(false, offline.online)
        // List payload should NOT carry detail-only fields even if the data
        // class definition permits them — we deliberately keep the list
        // contract minimal so the page can't accidentally fall through to a
        // detail-style render.
        assertEquals(null, online.hostname)
        assertEquals(null, online.platform)
        assertEquals(null, online.createdAt)
        assertEquals(null, online.apiKeyPrefix)
    }
}
