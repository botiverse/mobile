package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentModelLimitsWarningTest {
    @Test
    fun editorWarnsOnlyWhileTheDraftIsTheSavedBuiltInTarget() {
        // Web AgentProfileInfo (botiverse/slock #13492):
        // `defaultsReported && draftRuntime === "builtin" && !v2EditChanged`.
        assertTrue(membersAgentModelLimitsWarningInEditor(defaultsReported = true, selectedRuntimeId = "builtin", draftChanged = false))
        assertFalse(membersAgentModelLimitsWarningInEditor(defaultsReported = true, selectedRuntimeId = "builtin", draftChanged = true))
        assertFalse(membersAgentModelLimitsWarningInEditor(defaultsReported = true, selectedRuntimeId = "claude", draftChanged = false))
        assertFalse(membersAgentModelLimitsWarningInEditor(defaultsReported = false, selectedRuntimeId = "builtin", draftChanged = false))
    }
}
