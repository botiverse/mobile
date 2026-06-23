package ai.slock.compose.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AvatarPriorityTest {
    @Test
    fun agentAvatarPriorityRejectsGravatarAndKeepsPixelOrUploadedAvatar() {
        val pixelAvatar =
            resolveAvatarSlot(
                kind = MemberKind.Agent,
                id = "agent-1",
                avatarWireValue = "pixel:tree",
                gravatarHash = "hash"
            )
        assertEquals("pixel:tree", pixelAvatar.displayAvatarWireValue)
        assertNull(pixelAvatar.gravatarHash)
        assertEquals("pixel:robot", pixelAvatar.fallbackAvatarWireValue)
        assertEquals("pixel:tree", pixelAvatar.effectiveAvatarWireValue)

        assertEquals(
            "pixel:tree",
            avatarWireValueForAvatarSlot(
                kind = MemberKind.Agent,
                id = "agent-1",
                avatarWireValue = "pixel:tree",
                gravatarHash = "hash"
            )
        )
        assertEquals(
            "https://cdn.slock.ai/avatar.webp",
            avatarWireValueForAvatarSlot(
                kind = MemberKind.Agent,
                id = "agent-1",
                avatarWireValue = "https://cdn.slock.ai/avatar.webp"
            )
        )
        val gravatarAvatar =
            resolveAvatarSlot(
                kind = MemberKind.Agent,
                id = "agent-1",
                avatarWireValue = "https://www.gravatar.com/avatar/hash?s=52&d=404",
                gravatarHash = "hash"
            )
        assertNull(gravatarAvatar.displayAvatarWireValue)
        assertNull(gravatarAvatar.gravatarHash)
        assertEquals("pixel:robot", gravatarAvatar.fallbackAvatarWireValue)
        assertEquals("pixel:robot", gravatarAvatar.effectiveAvatarWireValue)
        assertEquals(
            "pixel:robot",
            avatarWireValueForAvatarSlot(
                kind = MemberKind.Agent,
                id = "agent-1",
                avatarWireValue = "https://www.gravatar.com/avatar/hash?s=52&d=404"
            )
        )
    }

    @Test
    fun humanAvatarPriorityAllowsGravatarFallback() {
        val humanGravatar =
            resolveAvatarSlot(
                kind = MemberKind.Human,
                id = "user-1",
                avatarWireValue = null,
                gravatarHash = "hash"
            )
        assertNull(humanGravatar.displayAvatarWireValue)
        assertEquals("hash", humanGravatar.gravatarHash)
        assertNull(humanGravatar.fallbackAvatarWireValue)
        assertEquals("https://www.gravatar.com/avatar/hash?s=52&d=404", humanGravatar.effectiveAvatarWireValue)

        assertEquals(
            "https://www.gravatar.com/avatar/hash?s=52&d=404",
            avatarWireValueForAvatarSlot(
                kind = MemberKind.Human,
                id = "user-1",
                avatarWireValue = null,
                gravatarHash = "hash"
            )
        )
        assertNull(
            avatarWireValueForAvatarSlot(
                kind = MemberKind.Human,
                id = "user-1",
                avatarWireValue = null,
                gravatarHash = null
            )
        )
    }

    @Test
    fun directMessageConversationUsesAgentAvatarRules() {
        val agentDm =
            ConversationSummary(
                threadId = ThreadId("dm-agent"),
                kind = ConversationKind.DirectMessage,
                name = "Build Agent",
                directMessagePeerType = "agent",
                avatarUrl = "https://www.gravatar.com/avatar/agent-hash?s=52&d=404",
                gravatarUrl = "https://www.gravatar.com/avatar/agent-hash?s=52&d=404"
            )

        val resolvedAgent = agentDm.avatarSlotResolution()

        assertNull(resolvedAgent.displayAvatarWireValue)
        assertNull(resolvedAgent.gravatarHash)
        assertEquals("pixel:robot", resolvedAgent.fallbackAvatarWireValue)
        assertEquals("pixel:robot", resolvedAgent.effectiveAvatarWireValue)

        val humanDm =
            ConversationSummary(
                threadId = ThreadId("dm-human"),
                kind = ConversationKind.DirectMessage,
                name = "Artin",
                directMessagePeerType = "human",
                gravatarUrl = "https://www.gravatar.com/avatar/artin?s=52&d=404"
            )

        assertEquals(
            "https://www.gravatar.com/avatar/artin?s=52&d=404",
            humanDm.avatarSlotResolution().effectiveAvatarWireValue
        )
    }
}
