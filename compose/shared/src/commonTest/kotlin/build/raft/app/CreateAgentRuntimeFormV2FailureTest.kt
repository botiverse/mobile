package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task #8 (retire runtime form v1, artin / HaoHao #wg-runtime-form f03a0c5a): once runtime form
 * v2 applies to the create dialog, a failed load stays in v2 (warning + Retry, create disabled)
 * and never falls back to the v1 runtime fields, whatever runtime is picked.
 */
class CreateAgentRuntimeFormV2FailureTest {
    @Test
    fun aFailedV2LoadKeepsTheSectionOnV2WithItsCode() {
        assertEquals(
            CreateAgentRuntimeSection.V2Failed("runtime_form_v2_http_503"),
            createAgentRuntimeSection(v2Candidate = true, load = RuntimeFormV2DialogLoad.Failed("runtime_form_v2_http_503"))
        )
    }

    @Test
    fun aV2CandidateIsOnV2WhileLoading() {
        assertEquals(CreateAgentRuntimeSection.V2Loading, createAgentRuntimeSection(true, RuntimeFormV2DialogLoad.Loading))
        assertEquals(CreateAgentRuntimeSection.V2Loading, createAgentRuntimeSection(true, RuntimeFormV2DialogLoad.Idle))
    }

    @Test
    fun onlyANonCandidateUsesV1() {
        // Flag off, external, or no computer/runtime yet.
        assertEquals(CreateAgentRuntimeSection.V1, createAgentRuntimeSection(false, RuntimeFormV2DialogLoad.Idle))
        assertEquals(CreateAgentRuntimeSection.V1, createAgentRuntimeSection(false, RuntimeFormV2DialogLoad.Failed("x")))
    }

    @Test
    fun createIsDisabledUntilTheV2FormHasLoaded() {
        assertFalse(createAgentRuntimeFormV2SubmitReady(formLoaded = false, hasErrors = false))
        assertFalse(createAgentRuntimeFormV2SubmitReady(formLoaded = true, hasErrors = true))
        assertTrue(createAgentRuntimeFormV2SubmitReady(formLoaded = true, hasErrors = false))
    }
}
