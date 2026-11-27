package build.raft.app

import build.raft.app.members.modelLabel
import build.raft.app.members.raftProfilePreviewCardModel
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RaftMemberModelLabelTest {

    @Test
    fun modelLabelReturnsNullForHumanMember() {
        val profile = memberProfile(kind = MemberKind.Human)
        assertNull(profile.modelLabel())
    }

    @Test
    fun modelLabelResolvesKnownRuntimeAndModel() {
        val profile = memberProfile(kind = MemberKind.Agent, roleDescription = "claude", model = "sonnet")
        assertEquals("Claude Sonnet", profile.modelLabel())
    }

    @Test
    fun modelLabelFallsBackToRawIdForUnknownRuntimeModel() {
        val profile = memberProfile(kind = MemberKind.Agent, roleDescription = "claude", model = "custom-id")
        assertEquals("custom-id", profile.modelLabel())
    }

    @Test
    fun anExternalOrCloudAgentShowsWhatTheWebShows() {
        // Web ProfilePreviewCardContent: Model = getModelLabel(runtime, model); shared RUNTIME_MODELS
        // lists the external sentinel (`{ id: "external", label: "External agent" }`), so an
        // external/cloud agent's card reads "External agent", as its Runtime row does
        // (formatRuntimeLabelWithStatus). MessageItem's agentModelLabel likewise (the cloud wording
        // needs `hostedRuntime`, not on MemberProfile).
        val external = memberProfile(kind = MemberKind.Agent, roleDescription = "external", model = "external")
        assertEquals("External agent", external.modelLabel())
        val card = raftProfilePreviewCardModel(external.copy(isExternal = true))
        assertEquals("External agent", card.modelLabel)
        assertEquals("External agent", card.runtimeLabel, "the runtime row names the sentinel, never the raw id")
    }

    private fun memberProfile(
        kind: MemberKind,
        roleDescription: String? = null,
        model: String? = null,
    ) = MemberProfile(
        id = MemberId("agent-1"),
        displayName = "Test Agent",
        handle = "test-agent",
        kind = kind,
        roleDescription = roleDescription,
        model = model,
    )
}
