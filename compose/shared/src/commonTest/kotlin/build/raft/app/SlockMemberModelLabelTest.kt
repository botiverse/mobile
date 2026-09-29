package build.raft.app

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlockMemberModelLabelTest {

    @Test
    fun modelLabelReturnsNullForHumanMember() {
        val profile = memberProfile(kind = MemberKind.Human)
        assertNull(profile.modelLabel())
    }

    @Test
    fun modelLabelReturnsNullForSystemMember() {
        val profile = memberProfile(kind = MemberKind.System)
        assertNull(profile.modelLabel())
    }

    @Test
    fun modelLabelReturnsNullForAgentWithoutModel() {
        val profile = memberProfile(kind = MemberKind.Agent, roleDescription = "claude", model = null)
        assertNull(profile.modelLabel())
    }

    @Test
    fun modelLabelReturnsNullForBlankModel() {
        val profile = memberProfile(kind = MemberKind.Agent, roleDescription = "claude", model = "  ")
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
    fun modelLabelUsesModelOverMissingRuntime() {
        // When runtime is absent (older payloads) getModelLabel falls back to
        // the raw model id — same behavior as the web lookup.
        val profile = memberProfile(kind = MemberKind.Agent, roleDescription = null, model = "gpt-6-astra")
        assertEquals("gpt-6-astra", profile.modelLabel())
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
