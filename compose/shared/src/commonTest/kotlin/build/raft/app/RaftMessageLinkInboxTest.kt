package build.raft.app

import build.raft.app.model.ConversationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A message link opened signed out waits through sign-in and is opened once afterwards
 * (#proj-mobile #25/#26, frame d ⑤). The shell's take rule is [raftMessageLinkShouldTake] over
 * [RaftMessageLinkInbox].
 */
class RaftMessageLinkInboxTest {
    private var now = 1_000_000L
    private val inbox = RaftMessageLinkInbox(nowEpochMillis = { now }, ttlMillis = 30L * 60L * 1000L)
    private val link = RaftMessageLink("lagoon-push-test", ConversationKind.Channel, "c1", messageId = "m1")

    /** One shell pass: what the AppShell's effect does with the current session state. */
    private fun shellPass(sessionReady: Boolean): RaftPendingMessageLink? {
        val pending = inbox.pending.value
        if (!raftMessageLinkShouldTake(pending, sessionReady)) return null
        return inbox.take(pending!!.id)
    }

    @Test
    fun linkWaitsWhileSignedOutAndOpensOnceAfterSignIn() {
        val submitted = inbox.submit(link, source = "android_view")

        // Signed out: the auth gate shows; restoring; choosing a server — nothing is taken.
        repeat(3) { assertNull(shellPass(sessionReady = false)) }
        assertEquals(submitted, inbox.pending.value)

        // Sign-in takes 10 minutes; the new shell then has a ready session.
        now += 10L * 60L * 1000L
        val taken = shellPass(sessionReady = true)
        assertNotNull(taken)
        assertEquals(link, taken.link)
        assertEquals("android_view", taken.source)

        // Exactly once: a recomposition, a second shell or a recreated Activity gets nothing.
        assertNull(inbox.pending.value)
        assertNull(shellPass(sessionReady = true))
        assertNull(inbox.take(submitted.id))
    }

    @Test
    fun abandonedSignInDropsTheLinkAfterItsTtl() {
        val submitted = inbox.submit(link, source = "ios_scene_continue")
        now += 30L * 60L * 1000L + 1
        assertNull(shellPass(sessionReady = true))
        assertNull(inbox.pending.value, "an expired link is cleared, not retried")
        assertNull(inbox.take(submitted.id))
    }

    @Test
    fun newerLinkReplacesTheOneWaitingForSignIn() {
        val first = inbox.submit(link, source = "android_view")
        val newerLink = link.copy(channelId = "c2", messageId = null)
        val second = inbox.submit(newerLink, source = "android_view")
        assertTrue(second.id > first.id)

        assertNull(inbox.take(first.id), "a replaced link is never opened")
        assertEquals(newerLink, shellPass(sessionReady = true)?.link)
    }

    @Test
    fun takeRuleNeedsBothALinkAndAReadySession() {
        assertFalse(raftMessageLinkShouldTake(null, sessionReady = true))
        val pending = inbox.submit(link, source = "test")
        assertFalse(raftMessageLinkShouldTake(pending, sessionReady = false))
        assertTrue(raftMessageLinkShouldTake(pending, sessionReady = true))
    }

    @Test
    fun clearDropsAPendingLink() {
        inbox.submit(link, source = "test")
        inbox.clear()
        assertNull(inbox.pending.value)
        assertNull(shellPass(sessionReady = true))
    }
}
