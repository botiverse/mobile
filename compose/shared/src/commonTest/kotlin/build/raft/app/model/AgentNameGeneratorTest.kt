package build.raft.app.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentNameGeneratorTest {

    private val allPairs: List<String> =
        AGENT_NAME_ADJECTIVES.flatMap { adjective -> AGENT_CLASSIC_NAMES.map { name -> "$adjective-$name" } }

    @Test
    fun everyNameBarePairedOrNumberedIsAValidAgentName() {
        for (word in AGENT_CLASSIC_NAMES + AGENT_NAME_ADJECTIVES) {
            assertTrue(Regex("^[A-Z][a-z]+$").matches(word), "unexpected word $word")
        }
        for (name in AGENT_CLASSIC_NAMES) assertNull(validateAgentName(name), "expected $name to be valid")
        for (pair in allPairs) assertNull(validateAgentName("${pair}99"), "expected ${pair}99 to be valid")
    }

    @Test
    fun wordListsMatchWebAndHaveNoDuplicates() {
        // Same lists as slock packages/shared/src/agentNameGenerator.ts.
        assertEquals(LIST_SIZES, AGENT_CLASSIC_NAMES.size to AGENT_NAME_ADJECTIVES.size)
        assertEquals("Ada", AGENT_CLASSIC_NAMES.first())
        assertEquals("Zoe", AGENT_CLASSIC_NAMES.last())
        for (list in listOf(AGENT_CLASSIC_NAMES, AGENT_NAME_ADJECTIVES)) {
            assertEquals(list.size, list.map { it.lowercase() }.toSet().size)
        }
        assertFalse(AGENT_CLASSIC_NAMES.any { it.equals("Cindy", ignoreCase = true) })
    }

    @Test
    fun picksAFreeClassicNameWhileAnyIsFree() {
        val onlyFree = AGENT_CLASSIC_NAMES[42]
        val exclude = AGENT_CLASSIC_NAMES.filter { it != onlyFree }.map { "  ${it.uppercase()} " }
        for (value in listOf(0.0, 0.25, 0.5, 0.999999, 1.0)) {
            assertEquals(onlyFree, suggestAgentName(exclude) { value })
        }
        assertEquals(AGENT_CLASSIC_NAMES.first(), suggestAgentName(emptyList()) { 0.0 })
        assertEquals(AGENT_CLASSIC_NAMES.last(), suggestAgentName(emptyList()) { 0.999999 })
        // A random value of exactly 1.0 is clamped to the last name rather than overflowing.
        assertEquals(AGENT_CLASSIC_NAMES.last(), suggestAgentName(emptyList()) { 1.0 })
    }

    @Test
    fun addsAnAdjectiveThenANumberOnceEveryClassicNameIsTaken() {
        val names = AGENT_CLASSIC_NAMES
        val first = "${AGENT_NAME_ADJECTIVES.first()}-${AGENT_CLASSIC_NAMES.first()}"
        assertEquals(first, suggestAgentName(names) { 0.0 })
        assertEquals(
            "${AGENT_NAME_ADJECTIVES.first()}-${AGENT_CLASSIC_NAMES[1]}",
            suggestAgentName(names + first.lowercase()) { 0.0 },
        )
        assertEquals(allPairs.last(), suggestAgentName(names) { 0.999999 })

        assertEquals("${first}2", suggestAgentName(names + allPairs) { 0.0 })
        assertEquals("${first}4", suggestAgentName(names + allPairs + "${first}2" + "${first.lowercase()}3") { 0.0 })
    }

    @Test
    fun defaultRandomSuggestionIsAValidClassicName() {
        val name = suggestAgentName()
        assertTrue(name in AGENT_CLASSIC_NAMES)
        assertNull(validateAgentName(name))
    }

    private companion object {
        val LIST_SIZES = 136 to 90
    }
}
