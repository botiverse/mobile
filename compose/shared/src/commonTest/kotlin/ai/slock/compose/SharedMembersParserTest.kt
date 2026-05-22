package ai.slock.compose

import ai.slock.compose.model.MemberKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedMembersParserTest {
    @Test
    fun agentsEndpointArrayParsesAsAgentMembers() {
        val agents =
            parseSharedAgents(
                """
                [
                  {
                    "id": "agent-1",
                    "name": "Android Agent",
                    "status": "online",
                    "computerId": "machine-1"
                  }
                ]
                """.trimIndent()
            )

        assertEquals(1, agents.size)
        assertEquals("agent-1", agents.single().id.value)
        assertEquals("Android Agent", agents.single().displayName)
        assertEquals(MemberKind.Agent, agents.single().kind)
    }

    @Test
    fun membersAndAgentsMergeKeepsAgentSectionWhenServerMembersOnlyHasHumans() {
        val humans =
            parseSharedMembers(
                """
                {
                  "members": [
                    {
                      "id": "human-1",
                      "displayName": "Artin",
                      "type": "human"
                    }
                  ]
                }
                """.trimIndent()
            )
        val agents =
            parseSharedAgents(
                """
                {
                  "agents": [
                    {
                      "id": "agent-1",
                      "displayName": "Cindy",
                      "type": "agent"
                    }
                  ]
                }
                """.trimIndent()
            )

        val merged = mergeSharedMembers(humans, agents)

        assertEquals(2, merged.size)
        assertTrue(merged.any { it.id.value == "human-1" && it.kind == MemberKind.Human })
        assertTrue(merged.any { it.id.value == "agent-1" && it.kind == MemberKind.Agent })
    }

    @Test
    fun mergeDoesNotDuplicateAgentsAlreadyPresentInServerMembers() {
        val members =
            parseSharedMembers(
                """
                {
                  "members": [
                    {
                      "id": "agent-1",
                      "displayName": "Cindy",
                      "type": "agent"
                    }
                  ]
                }
                """.trimIndent()
            )
        val agents =
            parseSharedAgents(
                """
                [
                  {
                    "id": "agent-1",
                    "displayName": "Cindy"
                  }
                ]
                """.trimIndent()
            )

        val merged = mergeSharedMembers(members, agents)

        assertEquals(1, merged.size)
        assertEquals(MemberKind.Agent, merged.single().kind)
    }
}
