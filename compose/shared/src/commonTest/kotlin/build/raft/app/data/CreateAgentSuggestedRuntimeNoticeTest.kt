package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The note under Create Agent's runtime picker for a card's suggested runtime, as web
 * `SuggestedRuntimeNotice` decides it since slock #14976 (botiverse/slock 8f25ae73e): no
 * "Suggested by <agent>" once it is the pick, only why it can't be used while it isn't.
 */
class CreateAgentSuggestedRuntimeNoticeTest {
    @Test
    fun aPreselectedSuggestionNeedsNoNote() {
        listOf(null, CreateAgentSuggestedRuntimeUnavailable.NotOnComputer).forEach { unavailable ->
            assertNull(createAgentSuggestedRuntimeNoticeCause(suggested = "codex", runtime = "codex", unavailable = unavailable))
        }
    }

    @Test
    fun aSuggestionTheComputerCantRunSaysWhy() {
        CreateAgentSuggestedRuntimeUnavailable.entries.forEach { cause ->
            assertEquals(cause, createAgentSuggestedRuntimeNoticeCause(suggested = "codex", runtime = "", unavailable = cause))
        }
    }

    @Test
    fun noSuggestionOrAnotherPickWithoutACauseSaysNothing() {
        assertNull(createAgentSuggestedRuntimeNoticeCause(suggested = null, runtime = "", unavailable = CreateAgentSuggestedRuntimeUnavailable.NotOnComputer))
        assertNull(createAgentSuggestedRuntimeNoticeCause(suggested = "", runtime = "", unavailable = CreateAgentSuggestedRuntimeUnavailable.NotAdmitted))
        assertNull(createAgentSuggestedRuntimeNoticeCause(suggested = "codex", runtime = "claude", unavailable = null))
    }
}
