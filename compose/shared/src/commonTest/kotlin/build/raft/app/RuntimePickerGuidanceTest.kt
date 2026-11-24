package build.raft.app

import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Web `runtimeCapabilityHint.ts` parity (botiverse/slock #15551 / #15599 / #16178, #triage:#534,
 * #proj-runtime:#94): each runtime's Recommended / Limited badge and capability hint, preferring
 * server/Computer-reported `raftCapabilities` and falling back to the vendored
 * `RUNTIME_RAFT_CAPABILITIES` and `RECOMMENDED_RUNTIME_IDS` only when absent.
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
    fun aDeprecatedRuntimeKeepsItsDeprecatedLabelAlone() {
        assertEquals(RuntimePickerGuidance(), runtimePickerGuidance("cursor", deprecated = true))
        assertEquals(RuntimePickerGuidance(), runtimePickerGuidance("claude", deprecated = true))
        assertTrue(runtimePickerGuidance("cursor", deprecated = false).limited)
    }

    @Test
    fun liveRaftCapabilitiesDriveLimitedAndHintsAndRecommendedShowsHintsOnlyForMachineReportedGaps() {
        // OpenCode on OAR steers mid-turn -> Limited badge clears and hint updates automatically.
        val opencodeOar =
            runtimePickerGuidance(
                runtimeId = "opencode",
                deprecated = false,
                capabilities =
                    ResolvedRuntimeRaftCapabilities(
                        midTurnSteering = true,
                        subagents = false,
                        compactionReported = false,
                        source = RuntimeCapabilitySource.Machine
                    )
            )
        assertEquals(
            RuntimePickerGuidance(limited = false, hint = RuntimeCapabilityHint.NoSubagentsCompactionUnreported),
            opencodeOar
        )

        // OpenCode on legacy (machine-reported) keeps Limited and full missing-capability hint.
        val opencodeLegacy =
            runtimePickerGuidance(
                runtimeId = "opencode",
                deprecated = false,
                capabilities =
                    ResolvedRuntimeRaftCapabilities(
                        midTurnSteering = false,
                        subagents = false,
                        compactionReported = false,
                        source = RuntimeCapabilitySource.Machine
                    )
            )
        assertEquals(
            RuntimePickerGuidance(limited = true, hint = RuntimeCapabilityHint.NoSteeringNoSubagentsCompactionUnreported),
            opencodeLegacy
        )

        // Cursor on OAR steers mid-turn -> Limited clears, hint reflects missing subagents only.
        val cursorOar =
            runtimePickerGuidance(
                runtimeId = "cursor",
                deprecated = false,
                capabilities =
                    ResolvedRuntimeRaftCapabilities(
                        midTurnSteering = true,
                        subagents = false,
                        compactionReported = true,
                        source = RuntimeCapabilitySource.Machine
                    )
            )
        assertEquals(RuntimePickerGuidance(limited = false, hint = RuntimeCapabilityHint.NoSubagents), cursorOar)

        // Grok on OAR gains subagents -> only compaction unreported remains.
        val grokOar =
            runtimePickerGuidance(
                runtimeId = "grok",
                deprecated = false,
                capabilities =
                    ResolvedRuntimeRaftCapabilities(
                        midTurnSteering = true,
                        subagents = true,
                        compactionReported = false,
                        source = RuntimeCapabilitySource.Default
                    )
            )
        assertEquals(RuntimePickerGuidance(limited = false, hint = RuntimeCapabilityHint.CompactionUnreported), grokOar)

        // Unknown future runtime still honors live Computer-reported capabilities.
        val futureRuntime =
            runtimePickerGuidance(
                runtimeId = "future-cli",
                deprecated = false,
                capabilities =
                    ResolvedRuntimeRaftCapabilities(
                        midTurnSteering = false,
                        subagents = true,
                        compactionReported = false,
                        source = RuntimeCapabilitySource.Machine
                    )
            )
        assertEquals(
            RuntimePickerGuidance(limited = true, hint = RuntimeCapabilityHint.NoSteeringCompactionUnreported),
            futureRuntime
        )

        // Recommended runtime on default (or unknown) fallback keeps Recommended badge alone (no hint).
        val codexDefault =
            runtimePickerGuidance(
                runtimeId = "codex",
                deprecated = false,
                capabilities =
                    ResolvedRuntimeRaftCapabilities(
                        midTurnSteering = true,
                        subagents = true,
                        compactionReported = false,
                        source = RuntimeCapabilitySource.Default
                    )
            )
        assertEquals(RuntimePickerGuidance(recommended = true), codexDefault)
        assertFalse(isMachineReportedCapabilities(ResolvedRuntimeRaftCapabilities(true, true, false, RuntimeCapabilitySource.Default)))
        assertFalse(isMachineReportedCapabilities(ResolvedRuntimeRaftCapabilities(true, true, false, RuntimeCapabilitySource.Unknown)))
        assertEquals(
            RuntimePickerGuidance(recommended = true),
            runtimePickerGuidance(
                runtimeId = "codex",
                deprecated = false,
                capabilities = ResolvedRuntimeRaftCapabilities(true, true, false, RuntimeCapabilitySource.Unknown)
            )
        )

        // Recommended runtime whose Computer explicitly reports a missing capability keeps Recommended AND shows the hint.
        val codexOldComputer =
            runtimePickerGuidance(
                runtimeId = "codex",
                deprecated = false,
                capabilities =
                    ResolvedRuntimeRaftCapabilities(
                        midTurnSteering = true,
                        subagents = true,
                        compactionReported = false,
                        source = RuntimeCapabilitySource.Machine
                    )
            )
        assertEquals(
            RuntimePickerGuidance(recommended = true, hint = RuntimeCapabilityHint.CompactionUnreported),
            codexOldComputer
        )

        // Recommended runtime never flips to Limited even if a Computer reports missing mid-turn steering; keeps Recommended + hint.
        val claudeNoSteering =
            runtimePickerGuidance(
                runtimeId = "claude",
                deprecated = false,
                capabilities =
                    ResolvedRuntimeRaftCapabilities(
                        midTurnSteering = false,
                        subagents = true,
                        compactionReported = false,
                        source = RuntimeCapabilitySource.Machine
                    )
            )
        assertEquals(
            RuntimePickerGuidance(recommended = true, limited = false, hint = RuntimeCapabilityHint.NoSteeringCompactionUnreported),
            claudeNoSteering
        )

        // Recommended runtime with all capabilities reported by machine stays hint-free.
        val claudeComplete =
            runtimePickerGuidance(
                runtimeId = "claude",
                deprecated = false,
                capabilities =
                    ResolvedRuntimeRaftCapabilities(
                        midTurnSteering = true,
                        subagents = true,
                        compactionReported = true,
                        source = RuntimeCapabilitySource.Machine
                    )
            )
        assertEquals(RuntimePickerGuidance(recommended = true), claudeComplete)

        // Deprecated runtime keeps only its deprecated label even when machine reports gaps.
        val deprecatedMachine =
            runtimePickerGuidance(
                runtimeId = "gemini",
                deprecated = true,
                capabilities =
                    ResolvedRuntimeRaftCapabilities(
                        midTurnSteering = false,
                        subagents = false,
                        compactionReported = false,
                        source = RuntimeCapabilitySource.Machine
                    )
            )
        assertEquals(RuntimePickerGuidance(), deprecatedMachine)
    }

    /** slock #16311 (`runtimeModelHint.test.ts`): slower mid-task reply hint for Antigravity Orchestrator models. */
    @Test
    fun antigravityOrchestratorModelsGetSlowerMidTaskReplyHint() {
        for (modelId in listOf(
            "gemini-pro-orchestrator",
            "gemini-pro-orchestrator-neon",
            "gemini-pro-orchestrator-argon",
            "gemini-pro-orchestrator-g1-pro",
            "GEMINI-PRO-ORCHESTRATOR-NEON",
            "  gemini-pro-orchestrator-neon  ",
            "Orchestrator (Neon)",
            "Orchestrator",
        )) {
            assertTrue(isAntigravityOrchestratorModel(modelId), modelId)
            assertEquals(
                Res.string.runtime_model_hint_antigravity_orchestrator,
                runtimeModelHint("antigravity", modelId),
                modelId,
            )
        }

        for (modelId in listOf(
            "",
            "   ",
            "gemini-3.1-pro-high",
            "claude-opus-4-6-thinking",
            "gpt-oss-120b-medium",
            "gemini-pro-orchestratorx",
            "orchestrator-helper",
        )) {
            assertFalse(isAntigravityOrchestratorModel(modelId), modelId)
            assertNull(runtimeModelHint("antigravity", modelId), modelId)
        }

        assertNull(runtimeModelHint("claude", "gemini-pro-orchestrator-neon"))
        assertNull(runtimeModelHint("codex", "gemini-pro-orchestrator-neon"))
        assertNull(runtimeModelHint(null, "gemini-pro-orchestrator-neon"))
        assertNull(runtimeModelHint("antigravity", ""))
    }
}
