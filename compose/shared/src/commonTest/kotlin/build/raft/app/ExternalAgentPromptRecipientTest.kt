package build.raft.app

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import kotlin.test.Test
import kotlin.test.assertEquals

/** Web `usePromptRecipientHandle` (botiverse/slock #15096): who the prompt's `dm:@…` names. */
class ExternalAgentPromptRecipientTest {
    private fun member(handle: String, kind: MemberKind) =
        MemberProfile(id = MemberId("id-$handle-$kind"), displayName = handle, handle = handle, kind = kind)

    @Test
    fun `recipient is the viewer handle and pinned to the human when an agent has the same name`() {
        data class Case(val viewer: String?, val members: List<MemberProfile>, val expected: String?)
        listOf(
            Case(viewer = null, members = emptyList(), expected = null),
            Case(viewer = " ", members = emptyList(), expected = null),
            Case(viewer = "nathan", members = emptyList(), expected = "nathan"),
            Case(viewer = "nathan", members = listOf(member("nathan", MemberKind.Human)), expected = "nathan"),
            Case(viewer = "nathan", members = listOf(member("nathan", MemberKind.Agent)), expected = "nathan~human"),
            Case(viewer = "nathan", members = listOf(member("Nathan", MemberKind.Agent)), expected = "nathan"),
        ).forEach { case ->
            assertEquals(case.expected, externalPromptRecipientHandle(case.viewer, case.members), "case=$case")
        }
    }
}
