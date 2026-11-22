package build.raft.app.assistant

import build.raft.app.model.MemberKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Web packages/web/src/assistant/AssistantContextChip.tsx and MessageInput withComposerReference
// (slock #12335; tests/assistantContextChip.behavior.test.tsx).
class ThreadAssistantContextTest {
    private val general = AssistantContextConversation.Channel("general")
    private val aliceAgent = AssistantContextConversation.DirectMessage(peerName = "alice", peerKind = MemberKind.Agent, displayName = "Alice")
    private val parentId = "1A2B3C4D-0000-4000-8000-000000000000"

    @Test
    fun aChannelIsItsNameAndItsToken() {
        assertEquals(
            AssistantContextRef(
                kind = AssistantContextKind.Channel,
                conversation = "#general",
                threadExcerpt = null,
                token = "#general",
                readable = true,
            ),
            assistantContextRef(general, thread = null, readsDms = false),
        )
    }

    @Test
    fun aDmCarriesThePeersKindAndIsReadableOnlyWhileTheOwnerLetsIt() {
        val ref = assistantContextRef(aliceAgent, thread = null, readsDms = false)!!
        assertEquals("dm:@alice~agent", ref.token)
        assertEquals("@Alice", ref.conversation)
        assertEquals(AssistantContextKind.DirectMessage, ref.kind)
        assertFalse(ref.readable)
        assertTrue(assistantContextRef(aliceAgent, thread = null, readsDms = true)!!.readable)
        val human = AssistantContextConversation.DirectMessage(peerName = "bob.lee", peerKind = MemberKind.Human, displayName = "")
        assertEquals("dm:@bob.lee~human:1a2b3c4d", assistantContextRef(human, AssistantContextThread(parentId, "hi"), readsDms = true)!!.token)
        assertEquals("@bob.lee", assistantContextRef(human, thread = null, readsDms = true)!!.conversation, "no display name: the handle")
        val unknownKind = AssistantContextConversation.DirectMessage(peerName = "carol", peerKind = null, displayName = "Carol")
        assertEquals("dm:@carol", assistantContextRef(unknownKind, thread = null, readsDms = true)!!.token)
    }

    @Test
    fun eachOpenAttachesAndRemoveOrSendDetaches() {
        val attachment = ThreadAssistantContextAttachment()
        attachment.origin = assistantContextRef(general, thread = null, readsDms = true)
        assertNull(attachment.context, "not opened yet")
        attachment.attach()
        assertEquals("#general", attachment.context?.token)
        attachment.detach()
        assertNull(attachment.context)
        // The next open attaches it afresh.
        attachment.attach()
        assertEquals("#general", attachment.context?.token)
        // Opened from somewhere with no conversation: nothing to attach.
        attachment.origin = null
        assertNull(attachment.context)
    }
}
