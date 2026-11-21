package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Web `runtimeCapabilityHint.ts` parity (botiverse/slock #15551 / #15599, #triage:#534): each
 * runtime's Recommended / Limited badge and capability hint, from the vendored
 * `RUNTIME_RAFT_CAPABILITIES` and `RECOMMENDED_RUNTIME_IDS`.
 */
class RuntimePickerGuidanceTest {
    @Test
    fun everyRuntimeGetsTheWebsBadgeAndHint() {
        val expected =
            mapOf(
                "claude" to RuntimePickerGuidance(recommended = true),
                "codex" to RuntimePickerGuidance(recommended = true),
                "grok" to RuntimePickerGuidance(hint = RuntimeCapabilityHint.NoSubagentsCompactionUnreported),
                "builtin" to RuntimePickerGuidance(hint = RuntimeCapabilityHint.NoSubagents),
                "antigravity" to RuntimePickerGuidance(hint = RuntimeCapabilityHint.NoSubagentsCompactionUnreported),
                "kimi-sdk" to RuntimePickerGuidance(),
                "kimi" to RuntimePickerGuidance(hint = RuntimeCapabilityHint.NoSubagents),
                "copilot" to RuntimePickerGuidance(limited = true, hint = RuntimeCapabilityHint.NoSteeringNoSubagentsCompactionUnreported),
                "cursor" to RuntimePickerGuidance(limited = true, hint = RuntimeCapabilityHint.NoSteeringNoSubagents),
                "gemini" to RuntimePickerGuidance(limited = true, hint = RuntimeCapabilityHint.NoSteeringNoSubagentsCompactionUnreported),
                "opencode" to RuntimePickerGuidance(limited = true, hint = RuntimeCapabilityHint.NoSteeringNoSubagentsCompactionUnreported),
                "pi" to RuntimePickerGuidance(hint = RuntimeCapabilityHint.NoSubagents),
                "qwen" to RuntimePickerGuidance(limited = true, hint = RuntimeCapabilityHint.NoSteeringCompactionUnreported),
                "qoder" to RuntimePickerGuidance(hint = RuntimeCapabilityHint.CompactionUnreported),
                "dsh" to RuntimePickerGuidance(),
                // Raft doesn't know it: nothing, and not Limited.
                "mystery" to RuntimePickerGuidance()
            )
        assertEquals(RuntimeRaftCapabilityTable.capabilities.keys + "mystery", expected.keys)
        expected.forEach { (id, guidance) -> assertEquals(guidance, runtimePickerGuidance(id, deprecated = false), id) }
    }

    @Test
    fun limitedIsTheMissingMidTurnSteeringNeverWithRecommended() {
        RuntimeRaftCapabilityTable.capabilities.forEach { (id, capabilities) ->
            assertEquals(!capabilities.midTurnSteering, isLimitedRuntime(id), id)
            val guidance = runtimePickerGuidance(id, deprecated = false)
            assertFalse(guidance.recommended && (guidance.limited || guidance.hint != null), id)
        }
        assertFalse(isLimitedRuntime("mystery"))
        assertEquals(listOf("claude", "codex"), RuntimeRaftCapabilityTable.recommendedRuntimeIds.filter(::isRecommendedRuntime))
    }

    @Test
    fun everyCombinationARuntimeHasHasCopy() {
        // Web: a combination no runtime has yet maps to nothing; one a runtime has must have copy.
        RuntimeRaftCapabilityTable.capabilities.forEach { (id, capabilities) ->
            val missing = missingRuntimeCapabilities(capabilities)
            if (missing.isNotEmpty()) assertNotNull(runtimeCapabilityHint(id), "$id lacks $missing")
        }
        assertEquals(
            "steering+subagents+compaction",
            missingRuntimeCapabilities(RuntimeRaftCapabilities(midTurnSteering = false, subagents = false, compactionReported = false))
        )
        assertEquals("", missingRuntimeCapabilities(RuntimeRaftCapabilities(midTurnSteering = true, subagents = true, compactionReported = true)))
    }

    @Test
    fun aDeprecatedRuntimeKeepsItsDeprecatedLabelAlone() {
        assertEquals(RuntimePickerGuidance(), runtimePickerGuidance("cursor", deprecated = true))
        assertEquals(RuntimePickerGuidance(), runtimePickerGuidance("claude", deprecated = true))
        assertTrue(runtimePickerGuidance("cursor", deprecated = false).limited)
    }
}
