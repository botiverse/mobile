package build.raft.app

import build.raft.app.data.ThreadActionCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadCreateAgentDialogStateTest {
    // task #193 (Vera's contract tooth): a previous request's raw server
    // message must never leak into a later request's error display. Every
    // transition that touches errorCode settles errorServerMessage too.
    @Test
    fun staleServerMessageNeverSurvivesIntoALaterRequest() {
        val card = ThreadActionCard(type = "agent:create", name = "@Draft")
        val firstFailure =
            ThreadCreateAgentActionDialogState(messageId = "m1", card = card)
                .submittingWith(card)
                .failedWith(
                    errorCode = "http_409",
                    errorServerMessage = "Agent name \"Nova\" is already taken"
                )
        assertEquals("Agent name \"Nova\" is already taken", firstFailure.errorServerMessage)

        // Second submit clears both error fields together.
        val secondSubmit = firstFailure.submittingWith(card)
        assertNull(secondSubmit.errorCode)
        assertNull(secondSubmit.errorServerMessage)

        // A later NoPermission-style failure carries no raw message: the old
        // request's text must not attach to the new blocker code.
        val blockerFailure = secondSubmit.failedWith(errorCode = "server_forbidden")
        assertEquals("server_forbidden", blockerFailure.errorCode)
        assertNull(blockerFailure.errorServerMessage)
    }

    @Test
    fun validationFailureAlsoResetsAnyEarlierServerMessage() {
        val card = ThreadActionCard(type = "agent:create", name = "@Draft")
        val afterServerFailure =
            ThreadCreateAgentActionDialogState(messageId = "m1", card = card)
                .failedWith(errorCode = "http_409", errorServerMessage = "old raw reason")

        val validation = afterServerFailure.failedWith("agent_name_required")

        assertEquals("agent_name_required", validation.errorCode)
        assertNull(validation.errorServerMessage)
    }
}
