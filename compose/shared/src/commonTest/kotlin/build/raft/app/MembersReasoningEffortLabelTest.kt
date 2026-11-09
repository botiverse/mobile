package build.raft.app

import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MembersReasoningEffortLabelTest {
    @Test
    fun sharedEffortIdsMapToWebLabels() {
        // Web `REASONING_EFFORT_LABEL_ID`: every shared `ReasoningEffort` id, incl. Pi's off/minimal/max.
        assertEquals(Res.string.agent_create_reasoning_off, membersReasoningEffortLabel("off"))
        assertEquals(Res.string.agent_create_reasoning_minimal, membersReasoningEffortLabel("minimal"))
        assertEquals(Res.string.agent_create_reasoning_low, membersReasoningEffortLabel("low"))
        assertEquals(Res.string.agent_create_reasoning_medium, membersReasoningEffortLabel("medium"))
        assertEquals(Res.string.agent_create_reasoning_high, membersReasoningEffortLabel("high"))
        assertEquals(Res.string.agent_create_reasoning_extra_high, membersReasoningEffortLabel("xhigh"))
        assertEquals(Res.string.agent_create_reasoning_max, membersReasoningEffortLabel("max"))
        assertEquals(Res.string.agent_create_reasoning_ultra, membersReasoningEffortLabel("ultra"))
    }

    @Test
    fun unknownEffortIdHasNoCatalogLabel() {
        // The profile row then falls back to the prettified raw id.
        assertNull(membersReasoningEffortLabel("turbo"))
        assertNull(membersReasoningEffortLabel(""))
    }
}
