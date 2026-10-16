package build.raft.app.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentNameGeneratorTest {

    private val allPairs: List<String> =
        AGENT_NAME_ADJECTIVES.flatMap { adjective -> AGENT_NAME_NOUNS.map { noun -> "$adjective-$noun" } }

    @Test
    fun everyAdjectiveNounPairIsAValidAgentName() {
        assertEquals(90, AGENT_NAME_ADJECTIVES.size)
        assertEquals(90, AGENT_NAME_NOUNS.size)
        for (name in allPairs) {
            assertNull(validateAgentName(name), "expected $name to be a valid agent name")
        }
    }

    @Test
    fun wordListsHaveNoDuplicatesAndEnoughCombinations() {
        assertEquals(AGENT_NAME_ADJECTIVES.size, AGENT_NAME_ADJECTIVES.toSet().size)
        assertEquals(AGENT_NAME_NOUNS.size, AGENT_NAME_NOUNS.toSet().size)
        assertTrue(AGENT_NAME_ADJECTIVES.size * AGENT_NAME_NOUNS.size >= 5000)
    }

    @Test
    fun picksTheOnlyFreeNameRegardlessOfRandomValue() {
        val last = allPairs.last()
        val exclude = allPairs.dropLast(1).toMutableList()
        exclude[0] = "  ${exclude[0].uppercase()} "
        assertEquals(last, suggestAgentName(exclude) { 0.0 })
        assertEquals(last, suggestAgentName(exclude) { 0.999 })
    }

    @Test
    fun randomValueIndexesIntoAllPairsInAdjectiveMajorOrder() {
        assertEquals("amber-badger", suggestAgentName(emptyList()) { 0.0 })
        assertEquals(allPairs.first(), suggestAgentName(emptyList()) { 0.0 })
        // index = floor(random * 8100): 0.999 lands on 8091, not the last pair; 0.9999 lands on 8099.
        assertEquals(allPairs[8091], suggestAgentName(emptyList()) { 0.999 })
        assertEquals("zesty-summit", suggestAgentName(emptyList()) { 0.9999 })
        assertEquals(allPairs.last(), suggestAgentName(emptyList()) { 0.9999 })
        // A random value of exactly 1.0 is clamped to the last pair rather than overflowing.
        assertEquals(allPairs.last(), suggestAgentName(emptyList()) { 1.0 })
    }

    @Test
    fun stillReturnsAListPairWhenEveryNameIsTaken() {
        for (value in listOf(0.0, 0.5, 0.999)) {
            val name = suggestAgentName(allPairs) { value }
            assertTrue(name in allPairs, "expected $name to be a generated pair")
        }
    }

    @Test
    fun defaultRandomSuggestionIsAValidPair() {
        val name = suggestAgentName()
        assertTrue(name in allPairs)
        assertNull(validateAgentName(name))
    }
}
