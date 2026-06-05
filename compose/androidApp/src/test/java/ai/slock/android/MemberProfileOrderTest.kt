package ai.slock.android

import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class MemberProfileOrderTest {
    @Test
    fun replaceMemberPreservingOrderKeepsClickedAgentInOriginalPosition() {
        val first = member("agent-1", "First Agent")
        val second = member("agent-2", "Second Agent")
        val third = member("agent-3", "Third Agent")

        val updated =
                listOf(first, second, third).replaceMemberPreservingOrder(
                        second.copy(displayName = "Second Agent Updated")
                )

        assertEquals(
                listOf("agent-1", "agent-2", "agent-3"),
                updated.map { it.id.value }
        )
        assertEquals("Second Agent Updated", updated[1].displayName)
    }

    @Test
    fun replaceMemberPreservingOrderAppendsOnlyWhenMemberIsNew() {
        val first = member("agent-1", "First Agent")
        val second = member("agent-2", "Second Agent")

        val updated =
                listOf(first).replaceMemberPreservingOrder(second)

        assertEquals(
                listOf("agent-1", "agent-2"),
                updated.map { it.id.value }
        )
    }

    private fun member(id: String, name: String): MemberProfile =
            MemberProfile(
                    id = MemberId(id),
                    displayName = name,
                    handle = "@$id",
                    kind = MemberKind.Agent
            )
}
